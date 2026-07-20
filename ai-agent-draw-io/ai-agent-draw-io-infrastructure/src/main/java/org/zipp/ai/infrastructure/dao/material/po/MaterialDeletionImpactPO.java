package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialDeletionImpactPO {
    private String materialId;
    private long lifecycleGeneration;
    private long versionCount;
    private long diagramCount;
    private long chartbookCount;
    private long citationCount;
}
