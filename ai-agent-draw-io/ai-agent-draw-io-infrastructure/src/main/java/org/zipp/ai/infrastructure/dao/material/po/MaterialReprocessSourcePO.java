package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialReprocessSourcePO {
    private String materialId;
    private String versionId;
    private Integer pageCount;
    private String contentSha256;
    private String latestRevisionId;
    private int latestRevisionNo;
    private String latestRevisionState;
    private String processingFingerprint;
    private String workerProfileFingerprint;
    private String parserVersion;
    private String cleanerVersion;
    private String chunkSchemaVersion;
    private String ocrVersion;
    private String vlmSchemaVersion;
    private String excludedPagesJson;
}
