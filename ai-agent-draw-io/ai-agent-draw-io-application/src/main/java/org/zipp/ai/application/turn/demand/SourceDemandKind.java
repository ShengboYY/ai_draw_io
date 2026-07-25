package org.zipp.ai.application.turn.demand;

// These kinds describe intent only; Probe still owns availability and authorization.

/** The only source branches exposed by this first resolver slice. */
public enum SourceDemandKind {
    CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
    OPTIONAL_DISCOVERY
}
