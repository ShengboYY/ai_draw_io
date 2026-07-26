package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextPinState;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Isolated grounded drawing path with a fixed generation port and one strong commit. */
public final class GroundedTurnHandler {

    private final GroundedGenerationPort generation;
    private final GroundedTurnCommitPort commit;
    private final TurnWriteGate writeGate;

    public GroundedTurnHandler(
            GroundedGenerationPort generation,
            GroundedTurnCommitPort commit,
            TurnWriteGate writeGate
    ) {
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
            String preparedEvidenceRef,
            ValidatedCitationManifest citations,
            Optional<DirectVisualProvenance> directProvenance,
            TurnEventSink events
    ) {
        SourceAwareHandlerChecks.requireCommon(
                attempt, context, readSet, plan.identity(), sourceBinding);
        Objects.requireNonNull(citations, "citations");
        Objects.requireNonNull(events, "events");
        events.publish(new TurnEvent("grounded_started", "prepared", Instant.now()));
        GroundedGenerationPort.Result result = Objects.requireNonNull(
                generation.generate(new GroundedGenerationPort.Request(
                        attempt, context, readSet, plan, preparedEvidenceRef)),
                "grounded generation");
        if (!result.citationManifestRef().equals(citations.manifestDigest())) {
            return new FencedCommitOutcome.Rejected("CITATION_MANIFEST_BINDING_MISMATCH");
        }
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
            outcome = commit.commit(new GroundedTurnCommit(
                    attempt,
                    sourceBinding,
                    context.request().diagramId(),
                    version,
                    digest,
                    result.canvasXml(),
                    result.assistantMessage(),
                    result.payloadRef(),
                    citations,
                    directProvenance));
        }
        if (outcome instanceof FencedCommitOutcome.Committed) {
            events.publish(new TurnEvent("grounded_committed", "persisted", Instant.now()));
        }
        return outcome;
    }
}
