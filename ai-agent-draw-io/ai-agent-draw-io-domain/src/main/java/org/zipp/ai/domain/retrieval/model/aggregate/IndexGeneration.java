package org.zipp.ai.domain.retrieval.model.aggregate;

import org.zipp.ai.domain.retrieval.model.valobj.IndexGenerationState;

import java.time.Instant;

public final class IndexGeneration {

    private final String id;
    private final String indexName;
    private final String embeddingModel;
    private final int dimension;
    private final String metric;
    private final String vectorSchemaVersion;
    private IndexGenerationState state = IndexGenerationState.BUILDING;
    private Instant activatedAt;

    private IndexGeneration(String id, String indexName, String embeddingModel, int dimension,
                            String metric, String vectorSchemaVersion) {
        this.id = requireText(id, "id");
        this.indexName = requireText(indexName, "indexName");
        this.embeddingModel = requireText(embeddingModel, "embeddingModel");
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
        this.metric = requireText(metric, "metric");
        this.vectorSchemaVersion = requireText(vectorSchemaVersion, "vectorSchemaVersion");
    }

    public static IndexGeneration building(String id, String indexName, String embeddingModel,
                                           int dimension, String metric, String vectorSchemaVersion) {
        return new IndexGeneration(id, indexName, embeddingModel, dimension, metric, vectorSchemaVersion);
    }

    public void beginShadow() {
        if (state != IndexGenerationState.BUILDING) {
            throw new IllegalStateException("only a building generation can enter shadow mode");
        }
        state = IndexGenerationState.SHADOW;
    }

    public void activate(Instant activationTime) {
        if (state != IndexGenerationState.SHADOW) {
            throw new IllegalStateException("shadow verification is required before activation");
        }
        state = IndexGenerationState.ACTIVE;
        activatedAt = java.util.Objects.requireNonNull(activationTime, "activationTime");
    }

    public void retire() {
        if (state != IndexGenerationState.ACTIVE) {
            throw new IllegalStateException("only an active generation can be retired");
        }
        state = IndexGenerationState.RETIRED;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public String indexName() { return indexName; }
    public String embeddingModel() { return embeddingModel; }
    public int dimension() { return dimension; }
    public String metric() { return metric; }
    public String vectorSchemaVersion() { return vectorSchemaVersion; }
    public IndexGenerationState state() { return state; }
    public Instant activatedAt() { return activatedAt; }
}
