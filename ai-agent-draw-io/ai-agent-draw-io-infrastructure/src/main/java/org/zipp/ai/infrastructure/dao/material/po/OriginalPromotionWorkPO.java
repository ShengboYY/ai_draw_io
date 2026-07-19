package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class OriginalPromotionWorkPO {
    private String uploadId;
    private long uploadGeneration;
    private String quarantineBucket;
    private String quarantineKey;
    private String quarantineVersionId;
    private String destinationKey;
    private String contentBlobId;
    private String materialId;
    private String versionId;
    private String revisionId;
    private String detectedMediaType;
    private long byteSize;
    private String contentSha256;
    private String processingFingerprint;
    private String formalObjectVersionId;
    private String formalEtag;
    private String formalChecksumSha256;
}
