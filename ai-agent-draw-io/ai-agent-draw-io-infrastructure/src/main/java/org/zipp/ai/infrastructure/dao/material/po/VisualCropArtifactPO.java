package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class VisualCropArtifactPO {
    private String revisionId;
    private String candidateId;
    private String pageId;
    private int pageNo;
    private String captionBlockId;
    private String objectKey;
    private String objectVersionId;
    private String contentSha256;
    private long byteSize;
    private String contentType;
}
