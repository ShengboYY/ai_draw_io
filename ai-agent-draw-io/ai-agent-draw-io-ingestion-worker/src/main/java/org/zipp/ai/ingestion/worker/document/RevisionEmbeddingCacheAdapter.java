package org.zipp.ai.ingestion.worker.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingCacheValue;
import org.zipp.ai.domain.retrieval.port.EmbeddingCachePort;

import java.util.Objects;
import java.util.Optional;

/** S3-backed disposable embedding cache kept separate from authoritative projection commits. */
public final class RevisionEmbeddingCacheAdapter implements EmbeddingCachePort {
    private static final long MAXIMUM_CACHE_BYTES = 64L * 1024 * 1024;
    private static final String CONTENT_TYPE = "application/json+gzip";

    private final RevisionArtifactPort artifacts;
    private final RevisionPageCodec codec;

    public RevisionEmbeddingCacheAdapter(RevisionArtifactPort artifacts, ObjectMapper objectMapper) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.codec = new RevisionPageCodec(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    public Optional<float[]> find(String revisionId, String cacheKey, int dimension) {
        StoredArtifact pin = artifacts.findImmutable(objectKey(revisionId, cacheKey),
                CONTENT_TYPE, MAXIMUM_CACHE_BYTES).orElse(null);
        if (pin == null) return Optional.empty();
        try {
            EmbeddingCacheValue cached = codec.decodeEmbeddingCacheValue(
                    artifacts.read(pin, MAXIMUM_CACHE_BYTES), MAXIMUM_CACHE_BYTES);
            float[] values = cached.values();
            if (!cacheKey.equals(cached.cacheKey()) || values.length != dimension) {
                throw new IllegalStateException("embedding cache identity is invalid");
            }
            return Optional.of(values);
        } catch (RuntimeException corrupted) {
            // Cache bytes are disposable; exact-version eviction lets the authoritative job recompute safely.
            artifacts.deleteExact(pin);
            return Optional.empty();
        }
    }

    @Override
    public void put(String revisionId, String cacheKey, float[] values) {
        EmbeddingCacheValue cached = new EmbeddingCacheValue("embedding-cache-v1", cacheKey, values);
        artifacts.putImmutable(objectKey(revisionId, cacheKey), codec.encode(cached), CONTENT_TYPE);
    }

    private String objectKey(String revisionId, String cacheKey) {
        if (revisionId == null || revisionId.isBlank() || cacheKey == null
                || !cacheKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("embedding cache identity is invalid");
        }
        return "revisions/" + revisionId.trim() + "/embedding-cache/" + cacheKey + ".vector.json.gz";
    }
}
