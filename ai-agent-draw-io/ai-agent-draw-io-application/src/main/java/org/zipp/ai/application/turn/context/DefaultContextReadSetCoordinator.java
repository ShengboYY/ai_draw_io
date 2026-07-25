package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;

import java.util.Objects;

/** Load-first coordinator that never lets a CAS loser continue with its own proposal. */
public final class DefaultContextReadSetCoordinator {

    private final ContextReadSetQueryPort query;
    private final ContextReadSetCommitPort commit;

    public DefaultContextReadSetCoordinator(
            ContextReadSetQueryPort query,
            ContextReadSetCommitPort commit
    ) {
        this.query = Objects.requireNonNull(query, "query");
        this.commit = Objects.requireNonNull(commit, "commit");
    }

    public ContextReadSetOutcome prepare(FencedAttempt attempt, ProposedContextReadSet proposal) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(proposal, "proposal");
        ContextReadSetLoadOutcome loaded = query.loadPinned(attempt);
        if (loaded instanceof ContextReadSetLoadOutcome.Found found) {
            return new ContextReadSetOutcome.Pinned(found.value());
        }
        if (loaded instanceof ContextReadSetLoadOutcome.Missing) {
            return commit.pinFirst(attempt, proposal);
        }
        if (loaded instanceof ContextReadSetLoadOutcome.FenceLost lost) {
            return new ContextReadSetOutcome.FenceLost(lost.status());
        }
        if (loaded instanceof ContextReadSetLoadOutcome.Revoked revoked) {
            return new ContextReadSetOutcome.Revoked(revoked.reason());
        }
        ContextReadSetLoadOutcome.Unavailable unavailable =
                (ContextReadSetLoadOutcome.Unavailable) loaded;
        return new ContextReadSetOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }
}
