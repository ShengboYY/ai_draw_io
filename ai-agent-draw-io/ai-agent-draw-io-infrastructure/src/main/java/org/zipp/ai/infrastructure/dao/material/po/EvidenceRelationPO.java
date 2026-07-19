package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class EvidenceRelationPO {
    private String fromEvidenceId;
    private String toEvidenceId;
    private String relationType;
    private double weight;
}
