package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextPinState;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;

import java.time.Instant;
import java.util.Objects;

/** Isolated Direct execution path with fixed tool-free ports and one strong commit. */
public final class DirectTurnHandler {

    private final DirectVisionPort vision;
    private final DirectGenerationPort generation;
    private final DirectTurnCommitPort commit;
    private final TurnWriteGate writeGate;

    public DirectTurnHandler(
            DirectVisionPort vision,
            DirectGenerationPort generation,
            DirectTurnCommitPort commit,
            TurnWriteGate writeGate
    ) {
        this.vision = Objects.requireNonNull(vision, "vision");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.commit = Objects.requireNonNull(commit, "commit");
        this.writeGate = Objects.requireNonNull(writeGate, "writeGate");
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            BoundSourcePlan plan,
            SourceCommitBinding sourceBinding,
            String artifactLeaseRef,
            String sourceIdentityRef,
            DirectCandidateOrigin origin,
            TurnEventSink events
    ) {
        SourceAwareHandlerChecks.requireCommon(
                attempt, context, readSet, plan.identity(), sourceBinding);
        Objects.requireNonNull(events, "events");
        events.publish(new TurnEvent("direct_started", "prepared", Instant.now()));
        DirectVisionPort.Observation observation = Objects.requireNonNull(
                vision.observe(new DirectVisionPort.Request(
                        attempt, plan, artifactLeaseRef)),
                "direct observation");
        DirectGenerationPort.Result result = Objects.requireNonNull(
                generation.generate(new DirectGenerationPort.Request(
                        attempt, context, readSet, plan, observation)),
                "direct generation");
        ContextSlicePin canvas = readSet.summary();
        long version = canvas.state() == ContextPinState.PINNED ? canvas.version() : 0;
        String digest = canvas.state() == ContextPinState.PINNED
                ? canvas.contentDigest() : "";
        var permit = writeGate.tryEnter(attempt);
        if (permit.isEmpty()) {
            return new FencedCommitOutcome.Rejected("TURN_WRITE_GATE_DISABLED");
        }
        FencedCommitOutcome outcome;
        try (TurnWriteGate.Permit ignored = permit.get()) {
            outcome = commit.commit(new DirectTurnCommit(
                    attempt,
                    sourceBinding,
                    context.request().diagramId(),
                    version,
                    digest,
                    result.canvasXml(),
                    result.assistantMessage(),
                    result.payloadRef(),
                    new DirectVisualProvenance(
                            observation.observationRef(),
                            sourceIdentityRef,
                            origin,
                            observation.observationFingerprint())));
        }
        if (outcome instanceof FencedCommitOutcome.Committed) {
            events.publish(new TurnEvent("direct_committed", "persisted", Instant.now()));
        }
        return outcome;
    }
}
