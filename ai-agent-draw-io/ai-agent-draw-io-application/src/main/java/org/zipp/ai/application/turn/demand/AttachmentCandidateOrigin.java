package org.zipp.ai.application.turn.demand;

/** Describes why an opaque attachment reference is eligible for this turn. */
public enum AttachmentCandidateOrigin {
    CURRENT_MESSAGE,
    RECENT_USER_MESSAGE
}
