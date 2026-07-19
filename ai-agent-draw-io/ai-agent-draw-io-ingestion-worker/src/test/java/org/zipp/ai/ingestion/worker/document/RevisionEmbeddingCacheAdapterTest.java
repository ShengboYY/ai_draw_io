package org.zipp.ai.ingestion.worker.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionEmbeddingCacheAdapterTest {

    @Test
    void corruptDisposableEntryIsEvictedAndCanBeRecomputed() {
        InMemoryArtifacts artifacts = new InMemoryArtifacts();
        String cacheKey = "c".repeat(64);
        String objectKey = "revisions/rev_1/embedding-cache/" + cacheKey + ".vector.json.gz";
        artifacts.putImmutable(objectKey, new byte[]{1, 2, 3}, "application/json+gzip");
        RevisionEmbeddingCacheAdapter cache = new RevisionEmbeddingCacheAdapter(
                artifacts, new ObjectMapper());

        assertTrue(cache.find("rev_1", cacheKey, 4).isEmpty());
        assertTrue(artifacts.deleted);

        float[] vector = new float[]{1, 2, 3, 4};
        cache.put("rev_1", cacheKey, vector);
        assertArrayEquals(vector, cache.find("rev_1", cacheKey, 4).orElseThrow());
    }

    private static final class InMemoryArtifacts implements RevisionArtifactPort {
        private StoredArtifact pin;
        private byte[] content;
        private boolean deleted;

        @Override public StoredArtifact putImmutable(String key, byte[] value, String type) {
            content = value.clone();
            pin = new StoredArtifact(key, "v1", sha256(value), value.length, type);
            return pin;
        }

        @Override public Optional<StoredArtifact> findImmutable(String key, String type, long maximumBytes) {
            return pin == null || !pin.objectKey().equals(key) ? Optional.empty() : Optional.of(pin);
        }

        @Override public boolean deleteExact(StoredArtifact artifact) {
            deleted = true;
            pin = null;
            content = null;
            return true;
        }

        @Override public byte[] read(StoredArtifact artifact, long maximumBytes) { return content.clone(); }
        @Override public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
            throw new UnsupportedOperationException();
        }

        private static String sha256(byte[] value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
