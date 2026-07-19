package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class EvidenceRegionPO {
    private String evidenceId;
    private String pageId;
    private int ordinal;
    private String bboxJson;
    private Integer displayCharStart;
    private Integer displayCharEnd;
    private String sourceBlockRef;
}
