package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialSectionPO {
    private String id;
    private String revisionId;
    private String parentSectionId;
    private int level;
    private int ordinal;
    private int pageStart;
    private int pageEnd;
    private String headingEvidenceId;
    private String structureHash;
}
