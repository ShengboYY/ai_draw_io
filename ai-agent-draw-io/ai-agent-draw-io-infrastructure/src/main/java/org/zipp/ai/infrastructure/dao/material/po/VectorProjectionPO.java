package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class VectorProjectionPO {
    private String retrievalChunkId;
    private String indexGenerationId;
    private int batchNo;
    private String indexName;
    private String namespace;
    private String vectorId;
    private String embeddingModel;
    private String embeddingFingerprint;
    private int dimension;
    private String projectionRole;
    private String state;
    private Instant indexedAt;
}
