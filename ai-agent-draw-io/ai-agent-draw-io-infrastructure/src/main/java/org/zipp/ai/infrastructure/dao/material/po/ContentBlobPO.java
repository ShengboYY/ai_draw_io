package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class ContentBlobPO {
    private String id;
    private String ownerType;
    private String ownerKey;
    private String contentSha256;
    private long byteSize;
    private String detectedMime;
    private String originalObjectKey;
    private String originalObjectVersionId;
    private String s3Etag;
    private String s3ChecksumSha256;
    private String status;
}
