package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.NoopTurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnLifecycleTraceEvent;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.planning.TurnRouteComputationOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteComputer;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.time.Duration;
import java.util.Objects;

/**
 * Load-first decision lifecycle. A CAS loser discards its computed route and reloads the
 * persisted winner before any handler can dispatch it.
 */
public final class DefaultTurnDecisionCoordinator implements TurnDecisionCoordinator {

    private static final int CHECKPOINT_SCHEMA_VERSION = 1;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final TurnDecisionCheckpointQueryPort query;
    private final TurnDecisionCheckpointCommitPort commit;
    private final TurnRouteComputer routeComputer;
    private final TurnRouteDecisionCodec codec;
    private final int maxAttempts;
    private final TurnLifecycleTracePort trace;

    public DefaultTurnDecisionCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit,
            TurnRouteComputer routeComputer,
            TurnRouteDecisionCodec codec
    ) {
        this(query, commit, routeComputer, codec, DEFAULT_MAX_ATTEMPTS,
                NoopTurnLifecycleTracePort.INSTANCE);
    }

    public DefaultTurnDecisionCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit,
            TurnRouteComputer routeComputer,
            TurnRouteDecisionCodec codec,
            TurnLifecycleTracePort trace
    ) {
        this(query, commit, routeComputer, codec, DEFAULT_MAX_ATTEMPTS, trace);
    }

    public DefaultTurnDecisionCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit,
            TurnRouteComputer routeComputer,
            TurnRouteDecisionCodec codec,
            int maxAttempts
    ) {
        this(query, commit, routeComputer, codec, maxAttempts,
                NoopTurnLifecycleTracePort.INSTANCE);
    }

    public DefaultTurnDecisionCoordinator(
            TurnDecisionCheckpointQueryPort query,
            TurnDecisionCheckpointCommitPort commit,
            TurnRouteComputer routeComputer,
            TurnRouteDecisionCodec codec,
            int maxAttempts,
            TurnLifecycleTracePort trace
    ) {
        this.query = Objects.requireNonNull(query, "query");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.routeComputer = Objects.requireNonNull(routeComputer, "routeComputer");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (maxAttempts <= 0 || maxAttempts > 8) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 8");
        }
        this.maxAttempts = maxAttempts;
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public TurnDecisionPreparationOutcome preparePinned(
            FencedAttempt attempt,
            UserTurnCommand command,
            BaseTurnContext context,
            ContextReadSet readSet
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(readSet, "readSet");
        if (readSet.messageHighWater() != attempt.contextMessageHighWater()) {
            return unavailable(TurnFailureCode.STALE_ATTEMPT, Duration.ZERO, attempt);
        }

        for (int attemptNumber = 0; attemptNumber < maxAttempts; attemptNumber++) {
            TurnDecisionCheckpointLoadOutcome loaded = query.loadPinned(attempt);
            if (loaded instanceof TurnDecisionCheckpointLoadOutcome.Found found) {
                trace.recordSafely(TurnLifecycleTraceEvent.decisionCheckpoint(
                        attempt, found.value().digest(), "LOADED"));
                return decodeFound(found.value(), attempt, readSet);
            }
            if (loaded instanceof TurnDecisionCheckpointLoadOutcome.Missing) {
                TurnRouteComputationOutcome computed = routeComputer.compute(
                        attempt, command, context, readSet);
                if (computed instanceof TurnRouteComputationOutcome.Unavailable unavailable) {
                    return unavailable(TurnFailureCode.TERMINAL_UNAVAILABLE,
                            unavailable.retryAfter(), attempt);
                }
                TurnRouteDecision decision = ((TurnRouteComputationOutcome.Ready) computed).decision();
                EncodedTurnRouteDecision encoded;
                try {
                    encoded = codec.encode(decision);
                } catch (RuntimeException exception) {
                    return unavailable(TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ZERO, attempt);
                }
                TurnDecisionCheckpoint proposal = TurnDecisionCheckpoint.create(
                        CHECKPOINT_SCHEMA_VERSION,
                        readSet.digest(),
                        attempt.inputBindingDigest(),
                        encoded.kind(),
                        encoded.json());
                TurnDecisionCheckpointOutcome pinned = commit.pinFirst(
                        attempt, new ProposedTurnDecisionCheckpoint(proposal));
                if (pinned instanceof TurnDecisionCheckpointOutcome.Pinned winner) {
                    trace.recordSafely(TurnLifecycleTraceEvent.decisionCheckpoint(
                            attempt, winner.value().digest(), "PINNED"));
                    return decodeFound(winner.value(), attempt, readSet);
                }
                if (pinned instanceof TurnDecisionCheckpointOutcome.Retry) {
                    trace.recordSafely(TurnLifecycleTraceEvent.decisionCheckpoint(
                            attempt, proposal.digest(), "CAS_RETRY"));
                    continue;
                }
                return mapCommitOutcome(pinned, attempt);
            }
            return mapLoadOutcome(loaded, attempt);
        }
        return unavailable(TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ZERO, attempt);
    }

    private TurnDecisionPreparationOutcome decodeFound(
            TurnDecisionCheckpoint checkpoint,
            FencedAttempt attempt,
            ContextReadSet readSet
    ) {
        if (!checkpoint.contextReadSetDigest().equals(readSet.digest())
                || !checkpoint.inputBindingDigest().equals(attempt.inputBindingDigest())) {
            return unavailable(TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ZERO, attempt);
        }
        try {
            return new TurnDecisionPreparationOutcome.Ready(codec.decode(checkpoint), checkpoint);
        } catch (RuntimeException exception) {
            return unavailable(TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ZERO, attempt);
        }
    }

    private TurnDecisionPreparationOutcome mapLoadOutcome(
            TurnDecisionCheckpointLoadOutcome loaded,
            FencedAttempt attempt
    ) {
        if (loaded instanceof TurnDecisionCheckpointLoadOutcome.FenceLost lost) {
            return new TurnDecisionPreparationOutcome.FenceLost(lost.status());
        }
        TurnDecisionCheckpointLoadOutcome.Unavailable unavailable =
                (TurnDecisionCheckpointLoadOutcome.Unavailable) loaded;
        return unavailable(unavailable.code(), unavailable.retryAfter(), attempt);
    }

    private TurnDecisionPreparationOutcome mapCommitOutcome(
            TurnDecisionCheckpointOutcome outcome,
            FencedAttempt attempt
    ) {
        if (outcome instanceof TurnDecisionCheckpointOutcome.FenceLost lost) {
            return new TurnDecisionPreparationOutcome.FenceLost(lost.status());
        }
        TurnDecisionCheckpointOutcome.Unavailable unavailable =
                (TurnDecisionCheckpointOutcome.Unavailable) outcome;
        return unavailable(unavailable.code(), unavailable.retryAfter(), attempt);
    }

    private TurnDecisionPreparationOutcome.Unavailable unavailable(
            TurnFailureCode code,
            Duration retryAfter,
            FencedAttempt attempt
    ) {
        return new TurnDecisionPreparationOutcome.Unavailable(
                new TurnStatusRef(attempt.key()), code, retryAfter);
    }
}
