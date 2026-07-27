package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class EvidenceUnitPO {
    private String id;
    private String versionId;
    private String revisionId;
    private String pageId;
    private String sectionId;
    private String unitType;
    private String modality;
    private String sourceChannel;
    private String displayTextObjectKey;
    private String displayTextObjectVersionId;
    private String visualObjectKey;
    private String visualObjectVersionId;
    private String displayTextSha256;
    private String qualityJson;
    private String status;
}
