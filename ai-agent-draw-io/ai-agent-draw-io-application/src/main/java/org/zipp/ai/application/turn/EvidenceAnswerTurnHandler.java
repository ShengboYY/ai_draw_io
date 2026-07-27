package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextPinState;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.time.Instant;
import java.util.Objects;

/** Required-evidence answer path; AI knowledge cannot be enabled by construction. */
public final class EvidenceAnswerTurnHandler {

    private final EvidenceAnswerGenerationPort generation;
    private final EvidenceAnswerTurnCommitPort commit;
    private final TurnWriteGate writeGate;

    public EvidenceAnswerTurnHandler(
            EvidenceAnswerGenerationPort generation,
            EvidenceAnswerTurnCommitPort commit,
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
            SourceCommitBinding sourceBinding,
            String preparedEvidenceRef,
            ValidatedCitationManifest citations,
            TurnEventSink events
    ) {
        return execute(attempt, context, readSet, sourceBinding, preparedEvidenceRef,
                citations, events, CancellationSignal.NEVER);
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourceCommitBinding sourceBinding,
            String preparedEvidenceRef,
            ValidatedCitationManifest citations,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        return execute(attempt, context, readSet, sourceBinding, preparedEvidenceRef,
                citations, false, events, cancellation);
    }

    public FencedCommitOutcome execute(
            FencedAttempt attempt,
            BaseTurnContext context,
            ContextReadSet readSet,
            SourceCommitBinding sourceBinding,
            String preparedEvidenceRef,
            ValidatedCitationManifest citations,
            boolean includeCanvasContext,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        SourceAwareHandlerChecks.requireCommon(
                attempt, context, readSet, sourceBinding.planIdentity(), sourceBinding);
        Objects.requireNonNull(citations, "citations");
        Objects.requireNonNull(events, "events");
        cancellation = cancellation == null ? CancellationSignal.NEVER : cancellation;
        events.publish(new TurnEvent("evidence_answer_started", "required", Instant.now()));
        EvidenceAnswerGenerationPort.Request request =
                new EvidenceAnswerGenerationPort.Request(
                        attempt,
                        context,
                        readSet,
                        sourceBinding.planIdentity(),
                        preparedEvidenceRef,
                        includeCanvasContext);
        if (request.aiKnowledgeAllowed()) {
            throw new IllegalStateException("EVIDENCE_ANSWER_AI_KNOWLEDGE_MUST_BE_DISABLED");
        }
        EvidenceAnswerGenerationPort.Result result = Objects.requireNonNull(
                generation.generate(request, cancellation),
                "evidence answer generation");
        if (!result.claimManifestRef().equals(citations.manifestDigest())) {
            return new FencedCommitOutcome.Rejected("CLAIM_MANIFEST_BINDING_MISMATCH");
        }
        ContextSlicePin canvas = readSet.summary();
        // A document-only answer is not coupled to Canvas mutations; a Canvas-specific answer
        // remains pinned so its response and citations describe the exact version it inspected.
        long version = includeCanvasContext && canvas.state() == ContextPinState.PINNED
                ? canvas.version() : 0;
        String digest = includeCanvasContext && canvas.state() == ContextPinState.PINNED
                ? canvas.contentDigest() : "";
        var permit = writeGate.tryEnter(attempt);
        if (permit.isEmpty()) {
            return new FencedCommitOutcome.Rejected("TURN_WRITE_GATE_DISABLED");
        }
        FencedCommitOutcome outcome;
        try (TurnWriteGate.Permit ignored = permit.get()) {
            outcome = commit.commit(new EvidenceAnswerTurnCommit(
                    attempt,
                    sourceBinding,
                    context.request().diagramId(),
                    version,
                    digest,
                    result.assistantMessage(),
                    result.payloadRef(),
                    citations));
        }
        if (outcome instanceof FencedCommitOutcome.Committed) {
            events.publish(new TurnEvent(
                    "evidence_answer_committed", "persisted", Instant.now()));
        }
        return outcome;
    }
}
