package org.zipp.ai.domain.retrieval;

/** User-visible source policy declaration; authorization is always resolved server-side. */
public enum SourceMode {
    NONE,
    AUTO,
    EXPLICIT,
    EXPLICIT_ONLY
}
