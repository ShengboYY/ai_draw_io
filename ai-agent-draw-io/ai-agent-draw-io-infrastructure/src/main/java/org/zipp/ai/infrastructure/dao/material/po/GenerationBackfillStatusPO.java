package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class GenerationBackfillStatusPO {
    private String generationId;
    private String generationState;
    private String activeGenerationId;
    private long targetGeneration;
    private int requiredRevisionCount;
    private int readyRevisionCount;
    private int expectedVectorCount;
    private int indexedVectorCount;
    private int readyManifestCount;
}
