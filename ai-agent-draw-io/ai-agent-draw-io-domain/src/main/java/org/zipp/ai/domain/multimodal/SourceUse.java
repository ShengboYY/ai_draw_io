package org.zipp.ai.domain.multimodal;

/** Source execution strategies; these do not grant access to a material version by themselves. */
public enum SourceUse {
    NONE,
    DIRECT,
    RETRIEVAL,
    DIRECT_AND_RETRIEVAL
}
