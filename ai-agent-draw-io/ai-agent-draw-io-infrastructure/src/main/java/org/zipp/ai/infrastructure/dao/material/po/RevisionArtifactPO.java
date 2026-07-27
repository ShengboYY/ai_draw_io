package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RevisionArtifactPO {
    private String id;
    private String revisionId;
    private int pageNo;
    private String artifactKind;
    private String objectKey;
    private String objectVersionId;
    private String contentSha256;
    private long byteSize;
    private String contentType;
}
