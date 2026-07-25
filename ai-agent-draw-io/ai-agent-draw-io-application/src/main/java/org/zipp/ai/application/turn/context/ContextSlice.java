package org.zipp.ai.application.turn.context;

/** Durable slices whose exact versions are fixed before any model invocation. */
public enum ContextSlice {
    SUMMARY,
    MEMBERSHIP,
    PROFILE,
    MEMORY
}
