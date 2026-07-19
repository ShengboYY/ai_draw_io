package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialPagePO {
    private String id;
    private String revisionId;
    private int pageNo;
    private double width;
    private double height;
    private String nativeTextStatus;
    private String ocrStatus;
    private Double ocrQuality;
    private String visualStatus;
    private String pageImageKey;
    private String rawExtractionKey;
    private String canonicalPageKey;
}
