package org.zipp.ai.application.turn.demand;

// These kinds describe intent only; Probe still owns availability and authorization.

/** Typed source branches proposed from current-instruction evidence. */
public enum SourceDemandKind {
    /** Compatibility value for checkpoints created before Direct and Retrieval were separated. */
    CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
    CURRENT_MESSAGE_DIRECT_REQUIRED,
    CURRENT_MESSAGE_RETRIEVAL_REQUIRED,
    CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL,
    CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED,
    OPTIONAL_DISCOVERY
}
