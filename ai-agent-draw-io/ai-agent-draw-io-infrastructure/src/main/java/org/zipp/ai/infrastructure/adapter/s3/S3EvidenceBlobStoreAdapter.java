package org.zipp.ai.infrastructure.adapter.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.domain.retrieval.port.EvidenceBlobStore;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/** Reads one exact S3 object version and verifies its size/hash through RevisionArtifactPort. */
public final class S3EvidenceBlobStoreAdapter implements EvidenceBlobStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RevisionArtifactPort artifacts;

    public S3EvidenceBlobStoreAdapter(RevisionArtifactPort artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public String readDisplayText(AuthorizedCandidate candidate, long maximumBytes) {
        byte[] content = readPinnedArtifact(candidate.displayArtifact(), maximumBytes);
        if (!"application/json+gzip".equalsIgnoreCase(candidate.displayArtifact().contentType())) {
            return new String(content, StandardCharsets.UTF_8);
        }
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content));
             InputStream bounded = new BoundedInputStream(gzip, maximumBytes)) {
            JsonNode root = JSON.readTree(bounded);
            String retrievalText = root.path("retrievalText").asText(null);
            if (retrievalText == null || retrievalText.isBlank()) {
                throw new IllegalStateException("retrieval artifact does not contain display text");
            }
            return retrievalText;
        } catch (IOException decodingFailure) {
            throw new IllegalStateException("retrieval artifact decoding failed", decodingFailure);
        }
    }

    private byte[] readPinnedArtifact(StoredArtifact pinned, long maximumBytes) {
        try {
            return artifacts.read(pinned, maximumBytes);
        } catch (RuntimeException legacyDigestFailure) {
            StoredArtifact current = artifacts.findImmutable(
                            pinned.objectKey(), pinned.contentType(), maximumBytes)
                    .filter(found -> pinned.objectVersionId().equals(found.objectVersionId())
                            && pinned.byteSize() == found.byteSize())
                    .orElseThrow(() -> legacyDigestFailure);
            // Older durable rows stored the semantic text digest. Re-read only when storage
            // proves that the current metadata still identifies the exact pinned object version.
            return artifacts.read(current, maximumBytes);
        }
    }

    /** Bounds decompressed JSON so a small gzip cannot exceed the hydration budget. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        private BoundedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                recordBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                recordBytes(count);
            }
            return count;
        }

        private void recordBytes(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > maximumBytes) {
                throw new IOException("decompressed retrieval artifact exceeds its byte budget");
            }
        }
    }
}
