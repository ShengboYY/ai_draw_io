package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialReprocessResultPO {
    private String materialId;
    private String versionId;
    private String revisionId;
    private int revisionNo;
    private String ingestState;
    private String revisionState;
    private String revisionStage;
    private int progress;
    private String processingFingerprint;
    private String excludedPagesJson;
    private String errorCode;
}
