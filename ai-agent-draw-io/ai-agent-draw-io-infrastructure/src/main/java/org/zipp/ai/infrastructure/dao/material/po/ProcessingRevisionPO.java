package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class ProcessingRevisionPO {
    private String id;
    private String versionId;
    private int revisionNo;
    private String fingerprint;
    private String state;
    private String stage;
    private int progress;
    private String parserVersion;
    private String cleanerVersion;
    private String chunkSchemaVersion;
    private String ocrVersion;
    private String vlmSchemaVersion;
    private String excludedPagesJson;
}
