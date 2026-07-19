package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class DocumentStructureArtifactPO {
    private String id;
    private String revisionId;
    private String artifactKind;
    private String objectKey;
    private String objectVersionId;
    private String contentSha256;
    private long byteSize;
    private String contentType;
}
