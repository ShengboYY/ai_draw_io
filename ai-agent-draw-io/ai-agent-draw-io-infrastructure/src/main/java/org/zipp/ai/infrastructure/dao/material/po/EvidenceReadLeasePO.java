package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class EvidenceReadLeasePO {
    private String id;
    private String ownerKey;
    private String materialId;
    private String versionId;
    private String revisionId;
    private String runId;
    private String status;
    private Instant expiresAt;
    private Instant maxExpiresAt;
    private Instant createdAt;
}
