package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialPageCatalogPO {
    private String materialId;
    private String versionId;
    private String revisionId;
    private int revisionNo;
    private String ingestState;
    private String revisionState;
    private String revisionStage;
    private int progress;
    private String excludedPagesJson;
    private Integer pageNo;
    private Double width;
    private Double height;
    private String nativeTextStatus;
    private String ocrStatus;
    private Double ocrQuality;
    private String visualStatus;
    private String errorCode;
    private Integer canonicalAvailable;
    private Integer previewAvailable;
}
