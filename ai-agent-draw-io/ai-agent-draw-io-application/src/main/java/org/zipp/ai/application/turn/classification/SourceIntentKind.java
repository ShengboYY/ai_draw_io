package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.SourceDemandKind;

/**
 * Bounded source intent emitted by the semantic router.
 *
 * <p>The model may describe what the user appears to want, but the deterministic source policy
 * still owns attachment binding, availability, authorization, and fallback.</p>
 */
public enum SourceIntentKind {
    NO_SOURCE,
    AMBIGUOUS,
    CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
    CURRENT_MESSAGE_DIRECT_REQUIRED,
    CURRENT_MESSAGE_RETRIEVAL_REQUIRED,
    CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL,
    CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED,
    OPTIONAL_DISCOVERY;

    public SourceDemandKind demandKind() {
        if (this == NO_SOURCE || this == AMBIGUOUS) {
            throw new IllegalStateException("source intent has no typed demand kind");
        }
        return SourceDemandKind.valueOf(name());
    }
}
