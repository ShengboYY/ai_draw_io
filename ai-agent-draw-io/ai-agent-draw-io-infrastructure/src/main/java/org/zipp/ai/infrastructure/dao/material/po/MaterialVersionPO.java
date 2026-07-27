package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialVersionPO {
    private String id;
    private String materialId;
    private String ownerKey;
    private int versionNo;
    private String contentBlobId;
    private String contentSha256;
    private String declaredMime;
    private String detectedMime;
    private long byteSize;
    private Integer pageCount;
    private String activeRevisionId;
    private String ingestState;
}
