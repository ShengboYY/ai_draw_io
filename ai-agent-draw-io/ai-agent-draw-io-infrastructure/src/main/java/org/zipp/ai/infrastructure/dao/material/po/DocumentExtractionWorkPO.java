package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class DocumentExtractionWorkPO {
    private String revisionId;
    private String detectedMediaType;
    private long revisionFenceGeneration;
    private long materialLifecycleGeneration;
    private String processingFingerprint;
    private String excludedPagesJson;
    private String objectKey;
    private String objectVersionId;
    private String contentSha256;
    private long byteSize;
    private String contentType;
}
