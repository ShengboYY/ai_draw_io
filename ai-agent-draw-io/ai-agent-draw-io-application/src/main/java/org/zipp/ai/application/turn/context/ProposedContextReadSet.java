package org.zipp.ai.application.turn.context;

/** Candidate assembled from server-owned facts before the fenced first-writer pin. */
public record ProposedContextReadSet(ContextReadSet value) {

    public ProposedContextReadSet {
        if (value == null) {
            throw new IllegalArgumentException("context read-set proposal must not be null");
        }
    }
}
