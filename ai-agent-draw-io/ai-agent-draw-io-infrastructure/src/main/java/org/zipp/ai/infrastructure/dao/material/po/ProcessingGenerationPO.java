package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class ProcessingGenerationPO {
    private long revisionFenceGeneration;
    private long materialLifecycleGeneration;
    private String processingFingerprint;
}
