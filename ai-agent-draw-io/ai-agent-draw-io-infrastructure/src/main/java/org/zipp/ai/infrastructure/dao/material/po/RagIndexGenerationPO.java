package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class RagIndexGenerationPO {
    private String id;
    private String indexName;
    private String namespace;
    private String embeddingModel;
    private String embeddingModelFingerprint;
    private int dimension;
    private String metric;
    private String vectorSchemaVersion;
    private String state;
    private Instant activatedAt;
    private Instant shadowStartedAt;
    private Instant retiredAt;
    private Instant purgedAt;
    private Instant rollbackUntil;
    private String activationReportId;
    private String previousGenerationId;
}
