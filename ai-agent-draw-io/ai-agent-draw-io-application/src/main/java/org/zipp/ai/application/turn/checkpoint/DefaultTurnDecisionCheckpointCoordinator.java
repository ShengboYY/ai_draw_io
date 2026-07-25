package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;

import java.time.Duration;
import java.util.Objects;

/** Load-first checkpoint coordinator; a CAS loser always uses the persisted winner. */
public final class DefaultTurnDecisionCheckpointCoordinator {

    private final TurnDecisionCheckpointQueryPort query;
    private final TurnDecisionCheckpointCommitPort commit;

    public DefaultTurnDecisionCheckpointCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit
    ) {
        this.query = Objects.requireNonNull(query, "query");
        this.commit = Objects.requireNonNull(commit, "commit");
    }

    public TurnDecisionCheckpointOutcome prepare(
            FencedAttempt attempt,
            ProposedTurnDecisionCheckpoint proposal
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(proposal, "proposal");
        TurnDecisionCheckpoint checkpoint = proposal.value();
        if (!attempt.inputBindingDigest().equals(checkpoint.inputBindingDigest())) {
            return new TurnDecisionCheckpointOutcome.Unavailable(
                    status(attempt),
                    TurnFailureCode.STALE_ATTEMPT,
                    Duration.ZERO);
        }
        TurnDecisionCheckpointLoadOutcome loaded = query.loadPinned(attempt);
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.Found found) {
            return new TurnDecisionCheckpointOutcome.Pinned(found.value());
        }
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.Missing) {
            return commit.pinFirst(attempt, proposal);
        }
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.FenceLost lost) {
            return new TurnDecisionCheckpointOutcome.FenceLost(lost.status());
        }
        TurnDecisionCheckpointLoadOutcome.Unavailable unavailable =
                (TurnDecisionCheckpointLoadOutcome.Unavailable) loaded;
        return new TurnDecisionCheckpointOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private TurnStatusRef status(FencedAttempt attempt) {
        return new TurnStatusRef(attempt.key());
    }
}
