package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialVersionMatchPO {
    private String materialId;
    private String versionId;
    private String revisionId;
    private String contentBlobId;
    private long materialLifecycleGeneration;
}
