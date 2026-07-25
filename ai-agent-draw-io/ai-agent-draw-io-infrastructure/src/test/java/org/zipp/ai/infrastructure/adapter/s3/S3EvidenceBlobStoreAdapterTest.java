package org.zipp.ai.infrastructure.adapter.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.infrastructure.adapter.filesystem.FileSystemMaterialObjectAdapter;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3EvidenceBlobStoreAdapterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsRetrievalTextFromThePinnedGzipJsonArtifact() throws Exception {
        byte[] content = gzipJson(Map.of(
                "schemaVersion", "retrieval-chunk-v1",
                "retrievalText", "标准编号是 GCB-2026-06。"));
        StoredArtifact artifact = new StoredArtifact("retrieval/chunk.json.gz", "version-1",
                "a".repeat(64), content.length, "application/json+gzip");
        var adapter = new S3EvidenceBlobStoreAdapter(new InMemoryArtifactPort(content));
        AuthorizedCandidate candidate = new AuthorizedCandidate(
                "chunk-1", "evidence-1", "material-1", "version-1", "revision-1",
                "TEXT", 1, 1.0, artifact, "guard-chartbook-scope-v1.pdf");

        assertEquals("标准编号是 GCB-2026-06。", adapter.readDisplayText(candidate, 64 * 1024));
    }

    @Test
    void recoversALegacySemanticDigestForTheSameExactFilesystemVersion() throws Exception {
        byte[] content = gzipJson(Map.of(
                "schemaVersion", "retrieval-chunk-v1",
                "retrievalText", "卸载资料后，新请求应立即不可检索。"));
        var artifacts = new FileSystemMaterialObjectAdapter(
                temporaryDirectory.resolve("quarantine"), temporaryDirectory.resolve("materials"));
        StoredArtifact artifact = artifacts.putImmutable(
                "material/revision/retrieval/chunk.json.gz", content, "application/json+gzip");
        var adapter = new S3EvidenceBlobStoreAdapter(artifacts);
        StoredArtifact legacyPin = new StoredArtifact(
                artifact.objectKey(), artifact.objectVersionId(), "b".repeat(64),
                artifact.byteSize(), artifact.contentType());
        AuthorizedCandidate candidate = new AuthorizedCandidate(
                "chunk-2", "evidence-2", "material-1", "version-1", "revision-1",
                "TEXT", 2, 1.0, legacyPin, "guard-chartbook-scope-v1.pdf");

        // Storage metadata repairs only the legacy digest; the pinned object version remains fixed.
        assertEquals("卸载资料后，新请求应立即不可检索。",
                adapter.readDisplayText(candidate, 64 * 1024));
    }

    @Test
    void refusesLegacyDigestRecoveryWhenTheObjectVersionDoesNotMatch() throws Exception {
        byte[] content = gzipJson(Map.of("retrievalText", "old text"));
        var artifacts = new FileSystemMaterialObjectAdapter(
                temporaryDirectory.resolve("quarantine-mismatch"),
                temporaryDirectory.resolve("materials-mismatch"));
        StoredArtifact artifact = artifacts.putImmutable(
                "material/revision/retrieval/mismatch.json.gz", content, "application/json+gzip");
        StoredArtifact stalePin = new StoredArtifact(
                artifact.objectKey(), "different-object-version", "b".repeat(64),
                artifact.byteSize(), artifact.contentType());
        AuthorizedCandidate candidate = new AuthorizedCandidate(
                "chunk-3", "evidence-3", "material-1", "version-1", "revision-1",
                "TEXT", 2, 1.0, stalePin, "guard-chartbook-scope-v1.pdf");

        assertThrows(IllegalArgumentException.class,
                () -> new S3EvidenceBlobStoreAdapter(artifacts)
                        .readDisplayText(candidate, 64 * 1024));
    }

    @Test
    void refusesLegacyDigestRecoveryWhenTheByteSizeDoesNotMatch() throws Exception {
        byte[] content = gzipJson(Map.of("retrievalText", "old text"));
        var artifacts = new FileSystemMaterialObjectAdapter(
                temporaryDirectory.resolve("quarantine-size-mismatch"),
                temporaryDirectory.resolve("materials-size-mismatch"));
        StoredArtifact artifact = artifacts.putImmutable(
                "material/revision/retrieval/size-mismatch.json.gz", content, "application/json+gzip");
        StoredArtifact stalePin = new StoredArtifact(
                artifact.objectKey(), artifact.objectVersionId(), "b".repeat(64),
                artifact.byteSize() + 1, artifact.contentType());
        AuthorizedCandidate candidate = new AuthorizedCandidate(
                "chunk-4", "evidence-4", "material-1", "version-1", "revision-1",
                "TEXT", 2, 1.0, stalePin, "guard-chartbook-scope-v1.pdf");

        // A matching object version alone cannot override a conflicting durable size pin.
        assertThrows(IllegalStateException.class,
                () -> new S3EvidenceBlobStoreAdapter(artifacts)
                        .readDisplayText(candidate, 64 * 1024));
    }

    private static byte[] gzipJson(Map<String, String> value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Match the immutable retrieval artifact representation written by the ingestion worker.
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            new ObjectMapper().writeValue(gzip, value);
        }
        return output.toByteArray();
    }

    private record InMemoryArtifactPort(byte[] content) implements RevisionArtifactPort {
        @Override
        public StoredArtifact putImmutable(String objectKey, byte[] value, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredArtifact> findImmutable(String objectKey, String contentType, long maximumBytes) {
            return Optional.empty();
        }

        @Override
        public byte[] read(StoredArtifact artifact, long maximumBytes) {
            return content.clone();
        }

        @Override
        public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
            throw new UnsupportedOperationException();
        }
    }
}
