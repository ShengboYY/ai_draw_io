package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextPinState;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTracePort;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerOutcome;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;
import org.zipp.ai.domain.retrieval.CancellationSignal;

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
    private final TurnWriteGate writeGate;
    private final PlainAgentTracePort trace;

    public PlainDrawingHandler(
            PlainGenerationPort generation,
            PlainTurnCommitPort commit,
            PlainRuntimeRegistry runtime,
            PlainExecutionProfile profile
    ) {
        this(generation, commit, runtime, profile, new AttemptWriteGate());
    }

    public PlainDrawingHandler(
            PlainGenerationPort generation,
            PlainTurnCommitPort commit,
            PlainRuntimeRegistry runtime,
            PlainExecutionProfile profile,
            TurnWriteGate writeGate
    ) {
        this(generation, commit, runtime, profile, writeGate, PlainAgentTracePort.NOOP);
    }

    public PlainDrawingHandler(
            PlainGenerationPort generation,
            PlainTurnCommitPort commit,
            PlainRuntimeRegistry runtime,
            PlainExecutionProfile profile,
            TurnWriteGate writeGate,
            PlainAgentTracePort trace
    ) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.profile = PlainExecutionProfile.requireM2SourceFree(profile);
        this.writeGate = Objects.requireNonNull(writeGate, "writeGate");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    /** Executes only a Plain route that has already passed Context and Decision checkpoints. */
    public FencedCommitOutcome execute(
            TurnV2PreHandlerOutcome.Ready prepared,
            TurnEventSink events
    ) {
        return execute(prepared, events, CancellationSignal.NEVER);
    }

    public FencedCommitOutcome execute(
            TurnV2PreHandlerOutcome.Ready prepared,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(prepared, "prepared");
        if (!(prepared.decision() instanceof TurnRouteDecision.Plain plain)) {
            throw new IllegalArgumentException("PLAIN_ROUTE_REQUIRED");
        }
        return execute(
                prepared.attempt(),
                prepared.context(),
                prepared.readSet(),
                plain.value().plan(),
                events,
                cancellation);
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            PlainDrawPlan plan,
            TurnEventSink events
    ) {
        return execute(attempt, context, readSet, plan, events, CancellationSignal.NEVER);
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            PlainDrawPlan plan,
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
            throw new IllegalArgumentException("PLAIN_CONTEXT_TURN_MISMATCH");
        }
        if (readSet.messageHighWater() != attempt.contextMessageHighWater()) {
            throw new IllegalArgumentException("PLAIN_CONTEXT_HIGH_WATER_MISMATCH");
        }
        if (!runtime.isSourceFree()) {
            throw new IllegalStateException("PLAIN_RUNTIME_NOT_SOURCE_FREE");
        }

        events.publish(new TurnEvent("plain_started", plan.action().name(), Instant.now()));
        PlainGenerationResult result = Objects.requireNonNull(
                generation.generate(
                        new PlainGenerationRequest(attempt, context, readSet, plan, profile),
                        events,
                        cancellation),
                "plain generation result");
        ContextSlicePin canvasPin = readSet.summary();
        long expectedCanvasVersion = canvasPin.state() == ContextPinState.PINNED
                ? canvasPin.version() : 0;
        String expectedCanvasContextDigest = canvasPin.state() == ContextPinState.PINNED
                ? canvasPin.contentDigest() : "";
        long commitStarted = System.nanoTime();
        trace(attempt, PlainAgentTraceType.COMMIT_STARTED, "STARTED", 0);
        var permit = writeGate.tryEnter(attempt);
        if (permit.isEmpty()) {
            trace(attempt, PlainAgentTraceType.COMMIT_COMPLETED,
                    "WRITE_GATE_REJECTED", elapsedMillis(commitStarted));
            return new FencedCommitOutcome.Rejected("TURN_WRITE_GATE_DISABLED");
        }
        FencedCommitOutcome outcome;
        try (TurnWriteGate.Permit ignored = permit.get()) {
            try {
                outcome = Objects.requireNonNull(
                        commit.commit(new PlainTurnCommit(
                                attempt,
                                plan.action(),
                                context.request().diagramId(),
                                expectedCanvasVersion,
                                expectedCanvasContextDigest,
                                result.canvasXml(),
                                result.assistantMessage(),
                                result.payloadRef())),
                        "plain commit outcome");
            } catch (RuntimeException failure) {
                trace(attempt, PlainAgentTraceType.COMMIT_COMPLETED,
                        "PLAIN_COMMIT_FAILED", elapsedMillis(commitStarted));
                throw failure;
            }
        }
        trace(attempt, PlainAgentTraceType.COMMIT_COMPLETED,
                commitOutcome(outcome), elapsedMillis(commitStarted));
        if (outcome instanceof FencedCommitOutcome.Committed) {
            events.publish(new TurnEvent("plain_committed", "persisted", Instant.now()));
        }
        return outcome;
    }

    private String commitOutcome(FencedCommitOutcome outcome) {
        if (outcome instanceof FencedCommitOutcome.Committed) {
            return "COMMITTED";
        }
        if (outcome instanceof FencedCommitOutcome.FenceLost) {
            return "FENCE_LOST";
        }
        if (outcome instanceof FencedCommitOutcome.AlreadyTerminal) {
            return "ALREADY_TERMINAL";
        }
        if (outcome instanceof FencedCommitOutcome.TerminalUnavailable) {
            return "TERMINAL_UNAVAILABLE";
        }
        return "REJECTED";
    }

    private void trace(
            FencedAttempt attempt,
            PlainAgentTraceType type,
            String outcome,
            long latencyMillis
    ) {
        // Commit trace is deliberately content-free; the lifecycle trace owns the terminal record.
        trace.recordSafely(new PlainAgentTraceEvent(
                attempt,
                0,
                type,
                "COMMIT",
                "plain_turn_commit",
                "",
                "",
                "",
                outcome,
                0,
                latencyMillis,
                Instant.now()));
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
