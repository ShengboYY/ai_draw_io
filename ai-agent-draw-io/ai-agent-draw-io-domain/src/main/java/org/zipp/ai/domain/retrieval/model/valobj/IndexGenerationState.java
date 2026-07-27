package org.zipp.ai.domain.retrieval.model.valobj;

public enum IndexGenerationState {
    BUILDING,
    SHADOW,
    ACTIVE,
    RETIRED,
    PURGING,
    PURGED
}
