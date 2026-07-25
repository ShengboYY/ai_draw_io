package org.zipp.ai.application.turn;

import java.time.Instant;
import java.util.Objects;

/**
 * Executes the isolated Plain path and performs one fenced strong commit.
 *
 * <p>No source-aware port appears in this constructor. Generation failures therefore stop
 * before commit, while a delivery sink failure cannot manufacture a product terminal.</p>
 */
public final class PlainDrawingHandler {

    private final PlainGenerationPort generation;
    private final PlainTurnCommitPort commit;
    private final PlainRuntimeRegistry runtime;
    private final PlainExecutionProfile profile;

    public PlainDrawingHandler(
            PlainGenerationPort generation,
            PlainTurnCommitPort commit,
            PlainRuntimeRegistry runtime,
            PlainExecutionProfile profile
    ) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            TurnEventSink events
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(events, "events");
        if (!runtime.isSourceFree()) {
            throw new IllegalStateException("PLAIN_RUNTIME_NOT_SOURCE_FREE");
        }

        events.publish(new TurnEvent("plain_started", plan.action().name(), Instant.now()));
        PlainGenerationResult result = Objects.requireNonNull(
                generation.generate(new PlainGenerationRequest(attempt, plan, profile), events),
                "plain generation result");
        FencedCommitOutcome outcome = Objects.requireNonNull(
                commit.commit(new PlainTurnCommit(attempt, result.payloadRef())),
                "plain commit outcome");
        if (outcome instanceof FencedCommitOutcome.Committed) {
            events.publish(new TurnEvent("plain_committed", "persisted", Instant.now()));
        }
        return outcome;
    }
}
