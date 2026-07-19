package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class VectorProjectionManifestPO {
    private String revisionId;
    private String indexGenerationId;
    private String objectKey;
    private String objectVersionId;
    private String contentSha256;
    private long byteSize;
    private String contentType;
    private String manifestHash;
    private int projectionCount;
    private String state;
}
