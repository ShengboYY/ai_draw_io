package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.time.Instant;
import java.util.Objects;

/**
 * Executes a source-free response and performs one fenced response commit.
 *
 * <p>The response path has no Canvas commit port. Generation failures therefore stop before the
 * assistant message and terminal outcome can be persisted.</p>
 */
public final class PlainResponseHandler {

    private final PlainResponseGenerationPort generation;
    private final ResponseTurnCommitPort commit;
    private final PlainExecutionProfile profile;
    private final TurnWriteGate writeGate;

    public PlainResponseHandler(
            PlainResponseGenerationPort generation,
            ResponseTurnCommitPort commit,
            PlainExecutionProfile profile
    ) {
        this(generation, commit, profile, new AttemptWriteGate());
    }

    public PlainResponseHandler(
            PlainResponseGenerationPort generation,
            ResponseTurnCommitPort commit,
            PlainExecutionProfile profile,
            TurnWriteGate writeGate
    ) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.profile = PlainExecutionProfile.requireM2SourceFree(profile);
        this.writeGate = Objects.requireNonNull(writeGate, "writeGate");
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            PlainResponsePlan plan,
            TurnEventSink events
    ) {
        return execute(attempt, context, readSet, plan, events, CancellationSignal.NEVER);
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            PlainResponsePlan plan,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(readSet, "readSet");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(events, "events");
        cancellation = cancellation == null ? CancellationSignal.NEVER : cancellation;
        if (!attempt.key().turnId().equals(context.request().turnId())) {
            throw new IllegalArgumentException("PLAIN_RESPONSE_CONTEXT_TURN_MISMATCH");
        }
        if (readSet.messageHighWater() != attempt.contextMessageHighWater()) {
            throw new IllegalArgumentException("PLAIN_RESPONSE_CONTEXT_HIGH_WATER_MISMATCH");
        }

        events.publish(new TurnEvent("plain_response_started", plan.kind().name(), Instant.now()));
        PlainResponseGenerationResult result = Objects.requireNonNull(
                generation.generate(
                        new PlainResponseGenerationRequest(attempt, context, readSet, plan, profile),
                        events,
                        cancellation),
                "plain response generation result");
        var permit = writeGate.tryEnter(attempt);
        if (permit.isEmpty()) {
            return new FencedCommitOutcome.Rejected("TURN_WRITE_GATE_DISABLED");
        }
        FencedCommitOutcome outcome;
        try (TurnWriteGate.Permit ignored = permit.get()) {
            outcome = Objects.requireNonNull(
                    commit.commit(new ResponseTurnCommit(
                            attempt,
                            context.request().diagramId(),
                            result.assistantMessage(),
                            result.payloadRef())),
                    "plain response commit outcome");
        }
        if (outcome instanceof FencedCommitOutcome.Committed) {
            events.publish(new TurnEvent("plain_response_committed", "persisted", Instant.now()));
        }
        return outcome;
    }
}
