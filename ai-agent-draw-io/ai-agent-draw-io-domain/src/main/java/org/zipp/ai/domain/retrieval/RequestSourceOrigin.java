package org.zipp.ai.domain.retrieval;

/** Server-authenticated reason that an exact source entered one request snapshot. */
public enum RequestSourceOrigin {
    ATTACHMENT,
    EXPLICIT,
    PINNED,
    AUTOMATIC
}
