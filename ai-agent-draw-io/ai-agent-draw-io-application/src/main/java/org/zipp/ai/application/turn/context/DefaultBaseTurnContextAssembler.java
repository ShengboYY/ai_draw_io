package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnInputBindingDigestCalculator;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.demand.CurrentInstruction;

import java.time.Duration;
import java.util.Objects;

/**
 * Coordinates the only legal Context lifecycle: load-first, candidate-on-missing, fenced pin,
 * then exact materialization. A CAS loser never returns its live candidate.
 */
public final class DefaultBaseTurnContextAssembler implements ContextAssemblyCoordinator {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ContextReadSetQueryPort readSets;
    private final ContextReadSetCommitPort readSetCommit;
    private final ContextCandidateQueryPort candidates;
    private final ContextReadSetMaterializerPort materializer;
    private final int maxAttempts;

    public DefaultBaseTurnContextAssembler(
            ContextReadSetQueryPort readSets,
            ContextReadSetCommitPort readSetCommit,
            ContextCandidateQueryPort candidates,
            ContextReadSetMaterializerPort materializer
    ) {
        this(readSets, readSetCommit, candidates, materializer, DEFAULT_MAX_ATTEMPTS);
    }

    public DefaultBaseTurnContextAssembler(
            ContextReadSetQueryPort readSets,
            ContextReadSetCommitPort readSetCommit,
            ContextCandidateQueryPort candidates,
            ContextReadSetMaterializerPort materializer,
            int maxAttempts
    ) {
        this.readSets = Objects.requireNonNull(readSets, "readSets");
        this.readSetCommit = Objects.requireNonNull(readSetCommit, "readSetCommit");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        if (maxAttempts <= 0 || maxAttempts > 8) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 8");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public ContextAssemblyOutcome assemble(FencedAttempt attempt, UserTurnCommand command) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        if (!attempt.key().turnId().equals(command.turnId())) {
            return unavailable(attempt, TurnFailureCode.STALE_ATTEMPT, Duration.ZERO);
        }
        if (!attempt.inputBindingDigest().equals(TurnInputBindingDigestCalculator.current(command))) {
            return unavailable(attempt, TurnFailureCode.STALE_ATTEMPT, Duration.ZERO);
        }

        for (int attemptNumber = 0; attemptNumber < maxAttempts; attemptNumber++) {
            ContextReadSetLoadOutcome loaded = readSets.loadPinned(attempt);
            if (loaded instanceof ContextReadSetLoadOutcome.Found found) {
                ContextAssemblyOutcome exact = materialize(attempt, command, found.value());
                if (!(exact instanceof ContextAssemblyOutcome.Retry)) {
                    return exact;
                }
                continue;
            }
            if (loaded instanceof ContextReadSetLoadOutcome.Missing) {
                ContextCandidateLoadOutcome candidate = candidates.loadCandidate(attempt, command);
                if (candidate instanceof ContextCandidateLoadOutcome.Ready ready) {
                    if (ready.value().readSet().messageHighWater() != attempt.contextMessageHighWater()) {
                        return unavailable(attempt, TurnFailureCode.STALE_ATTEMPT, Duration.ZERO);
                    }
                    ContextReadSetOutcome pinned = readSetCommit.pinFirst(
                            attempt, new ProposedContextReadSet(ready.value().readSet()));
                    if (pinned instanceof ContextReadSetOutcome.Pinned winner) {
                        ContextAssemblyOutcome exact = materialize(attempt, command, winner.value());
                        if (exact instanceof ContextAssemblyOutcome.Ready) {
                            return exact;
                        }
                        if (!(exact instanceof ContextAssemblyOutcome.Retry)) {
                            return exact;
                        }
                        continue;
                    }
                    if (pinned instanceof ContextReadSetOutcome.Retry) {
                        continue;
                    }
                    return mapPinnedOutcome(pinned, attempt);
                }
                if (candidate instanceof ContextCandidateLoadOutcome.Retry) {
                    continue;
                }
                return mapCandidateOutcome(candidate, attempt);
            }
            return mapLoadedOutcome(loaded, attempt);
        }
        return unavailable(attempt, TurnFailureCode.TERMINAL_UNAVAILABLE, Duration.ZERO);
    }

    @Override
    public ContextPreparationOutcome prepareBeforeRouter(
            FencedAttempt attempt,
            UserTurnCommand command
    ) {
        ContextAssemblyOutcome outcome = assemble(attempt, command);
        if (outcome instanceof ContextAssemblyOutcome.Ready ready) {
            // Preserve the exact pinned read set for the downstream decision checkpoint.
            return new ContextPreparationOutcome.Ready(ready.context(), ready.readSet());
        }
        if (outcome instanceof ContextAssemblyOutcome.Terminal terminal) {
            return new ContextPreparationOutcome.Terminal(terminal.code(), terminal.reason());
        }
        if (outcome instanceof ContextAssemblyOutcome.FenceLost lost) {
            return new ContextPreparationOutcome.FenceLost(lost.status());
        }
        if (outcome instanceof ContextAssemblyOutcome.Unavailable unavailable) {
            return new ContextPreparationOutcome.Unavailable(
                    unavailable.status(), unavailable.code(), unavailable.retryAfter());
        }
        throw new IllegalStateException("Context retry escaped bounded assembler");
    }

    private ContextAssemblyOutcome materialize(
            FencedAttempt attempt,
            UserTurnCommand command,
            ContextReadSet readSet
    ) {
        ContextMaterializationOutcome outcome = materializer.materialize(attempt, command, readSet);
        if (outcome instanceof ContextMaterializationOutcome.Ready ready) {
            return new ContextAssemblyOutcome.Ready(toBaseContext(command, ready.value()), readSet);
        }
        if (outcome instanceof ContextMaterializationOutcome.Retry) {
            return new ContextAssemblyOutcome.Retry();
        }
        if (outcome instanceof ContextMaterializationOutcome.FenceLost lost) {
            return new ContextAssemblyOutcome.FenceLost(lost.status());
        }
        if (outcome instanceof ContextMaterializationOutcome.Revoked revoked) {
            return new ContextAssemblyOutcome.Terminal("CONTEXT_READ_SET_REVOKED", revoked.reason());
        }
        ContextMaterializationOutcome.Unavailable unavailable =
                (ContextMaterializationOutcome.Unavailable) outcome;
        return new ContextAssemblyOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private BaseTurnContext toBaseContext(UserTurnCommand command, ContextMaterializedSlices slices) {
        return new BaseTurnContext(
                new CurrentRequestContext(
                        command.turnId(), command.diagramId(), new CurrentInstruction(command.content())),
                slices.attachments(),
                slices.activeClarification(),
                slices.canvas(),
                slices.selection(),
                slices.conversation(),
                slices.membership(),
                slices.chartbook(),
                slices.memory(),
                slices.diagnostics());
    }

    private ContextAssemblyOutcome mapLoadedOutcome(
            ContextReadSetLoadOutcome loaded,
            FencedAttempt attempt
    ) {
        if (loaded instanceof ContextReadSetLoadOutcome.FenceLost lost) {
            return new ContextAssemblyOutcome.FenceLost(lost.status());
        }
        if (loaded instanceof ContextReadSetLoadOutcome.Revoked revoked) {
            return new ContextAssemblyOutcome.Terminal("CONTEXT_READ_SET_REVOKED", revoked.reason());
        }
        ContextReadSetLoadOutcome.Unavailable unavailable =
                (ContextReadSetLoadOutcome.Unavailable) loaded;
        return new ContextAssemblyOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private ContextAssemblyOutcome mapCandidateOutcome(
            ContextCandidateLoadOutcome candidate,
            FencedAttempt attempt
    ) {
        if (candidate instanceof ContextCandidateLoadOutcome.FenceLost lost) {
            return new ContextAssemblyOutcome.FenceLost(lost.status());
        }
        if (candidate instanceof ContextCandidateLoadOutcome.Revoked revoked) {
            return new ContextAssemblyOutcome.Terminal("CONTEXT_CANDIDATE_REVOKED", revoked.reason());
        }
        ContextCandidateLoadOutcome.Unavailable unavailable =
                (ContextCandidateLoadOutcome.Unavailable) candidate;
        return new ContextAssemblyOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private ContextAssemblyOutcome mapPinnedOutcome(
            ContextReadSetOutcome pinned,
            FencedAttempt attempt
    ) {
        if (pinned instanceof ContextReadSetOutcome.FenceLost lost) {
            return new ContextAssemblyOutcome.FenceLost(lost.status());
        }
        if (pinned instanceof ContextReadSetOutcome.Revoked revoked) {
            return new ContextAssemblyOutcome.Terminal("CONTEXT_READ_SET_REVOKED", revoked.reason());
        }
        ContextReadSetOutcome.Unavailable unavailable =
                (ContextReadSetOutcome.Unavailable) pinned;
        return new ContextAssemblyOutcome.Unavailable(
                unavailable.status(), unavailable.code(), unavailable.retryAfter());
    }

    private ContextAssemblyOutcome unavailable(
            FencedAttempt attempt,
            TurnFailureCode code,
            Duration retryAfter
    ) {
        return new ContextAssemblyOutcome.Unavailable(new TurnStatusRef(attempt.key()), code, retryAfter);
    }

}
