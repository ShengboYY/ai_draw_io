package org.zipp.ai.application.turn.planning;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.AttemptWriteGate;
import org.zipp.ai.application.turn.DirectGenerationPort;
import org.zipp.ai.application.turn.DirectTurnCommit;
import org.zipp.ai.application.turn.DirectTurnHandler;
import org.zipp.ai.application.turn.DirectVisionPort;
import org.zipp.ai.application.turn.EvidenceAnswerGenerationPort;
import org.zipp.ai.application.turn.EvidenceAnswerTurnHandler;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.GroundedGenerationPort;
import org.zipp.ai.application.turn.GroundedTurnHandler;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.ValidatedCitationManifest;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class SourceAwareStrongCommitHandlerTest {

    private static final TurnKey TURN =
            new TurnKey("owner-1", "conversation-1", "turn-1");

    @Test
    void isolatedDirectPathUsesVisionGenerationAndOneStrongCommit() {
        FencedAttempt attempt = attempt();
        BoundSourcePlan plan = directPlan();
        SourceCommitBinding binding = binding(plan.identity());
        AtomicReference<DirectTurnCommit> captured = new AtomicReference<>();
        CancellationSignal cancellation = () -> false;
        AtomicReference<CancellationSignal> visionCancellation = new AtomicReference<>();
        AtomicReference<CancellationSignal> generationCancellation = new AtomicReference<>();
        List<TurnEvent> events = new ArrayList<>();
        DirectTurnHandler handler = new DirectTurnHandler(
                (request, signal) -> {
                    visionCancellation.set(signal);
                    return new DirectVisionPort.Observation(
                            "observation-ref", "observation-fingerprint");
                },
                (request, signal) -> {
                    generationCancellation.set(signal);
                    return new DirectGenerationPort.Result(
                            "payload-direct", "<mxGraphModel/>", "done");
                },
                command -> {
                    captured.set(command);
                    return committed("direct", "payload-direct");
                },
                new AttemptWriteGate());

        FencedCommitOutcome outcome = new IsolatedAllPathExecutor(
                handler, null, null).direct(
                attempt,
                context(),
                readSet(),
                plan,
                binding,
                "artifact-lease",
                "source-identity",
                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                events::add,
                cancellation);

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
        assertSame(cancellation, visionCancellation.get());
        assertSame(cancellation, generationCancellation.get());
        assertEquals("source-identity", captured.get().provenance().sourceIdentityRef());
        assertEquals("observation-fingerprint",
                captured.get().provenance().observationFingerprint());
        assertEquals(List.of("direct_started", "direct_committed"),
                events.stream().map(TurnEvent::type).toList());
    }

    @Test
    void groundedPathRequiresTheExactValidatedManifest() {
        FencedAttempt attempt = attempt();
        BoundSourcePlan plan = directPlan();
        SourceCommitBinding binding = binding(plan.identity());
        ValidatedCitationManifest manifest = manifest();
        int[] commits = {0};
        GroundedTurnHandler handler = new GroundedTurnHandler(
                (request, cancellation) -> new GroundedGenerationPort.Result(
                        "payload-grounded",
                        "<mxGraphModel/>",
                        "done",
                        "f".repeat(64)),
                command -> {
                    commits[0]++;
                    return committed("grounded", "payload-grounded");
                },
                new AttemptWriteGate());

        FencedCommitOutcome outcome = handler.execute(
                attempt,
                context(),
                readSet(),
                plan,
                binding,
                "prepared-evidence",
                manifest,
                Optional.empty(),
                event -> { });

        FencedCommitOutcome.Rejected rejected =
                assertInstanceOf(FencedCommitOutcome.Rejected.class, outcome);
        assertEquals("CITATION_MANIFEST_BINDING_MISMATCH", rejected.code());
        assertEquals(0, commits[0]);
    }

    @Test
    void evidenceAnswerIsRequiredEvidenceOnlyAndCannotEnableAiKnowledge() {
        FencedAttempt attempt = attempt();
        BoundSourcePlan plan = directPlan();
        SourceCommitBinding binding = binding(plan.identity());
        ValidatedCitationManifest manifest = manifest();
        int[] commits = {0};
        EvidenceAnswerTurnHandler handler = new EvidenceAnswerTurnHandler(
                (request, cancellation) -> {
                    assertFalse(request.aiKnowledgeAllowed());
                    assertFalse(request.includeCanvasContext());
                    return new EvidenceAnswerGenerationPort.Result(
                            "payload-answer", "supported answer", manifest.manifestDigest());
                },
                command -> {
                    commits[0]++;
                    assertEquals(manifest, command.citations());
                    assertEquals(0, command.expectedTargetCanvasVersion());
                    return committed("evidence_answer", "payload-answer");
                },
                new AttemptWriteGate());

        FencedCommitOutcome outcome = new IsolatedAllPathExecutor(
                null, null, handler).answer(
                attempt,
                context(),
                pinnedCanvasReadSet(),
                binding,
                "prepared-evidence",
                manifest,
                event -> { });

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
        assertEquals(1, commits[0]);
    }

    @Test
    void canvasSpecificEvidenceAnswerPinsOnlyTheCanvasItActuallyReads() {
        FencedAttempt attempt = attempt();
        BoundSourcePlan plan = directPlan();
        SourceCommitBinding binding = binding(plan.identity());
        ValidatedCitationManifest manifest = manifest();
        EvidenceAnswerTurnHandler handler = new EvidenceAnswerTurnHandler(
                (request, cancellation) -> {
                    assertEquals(true, request.includeCanvasContext());
                    return new EvidenceAnswerGenerationPort.Result(
                            "payload-answer", "supported answer", manifest.manifestDigest());
                },
                command -> {
                    assertEquals(5, command.expectedTargetCanvasVersion());
                    assertEquals("9".repeat(64), command.expectedTargetCanvasContextDigest());
                    return committed("evidence_answer", "payload-answer");
                },
                new AttemptWriteGate());

        FencedCommitOutcome outcome = handler.execute(
                attempt, context(), pinnedCanvasReadSet(), binding,
                "prepared-evidence", manifest, true, event -> { },
                CancellationSignal.NEVER);

        assertInstanceOf(FencedCommitOutcome.Committed.class, outcome);
    }

    @Test
    void citationValidationRejectsInsufficientAndOutOfSnapshotClaims() {
        var unsupported = ValidatedCitationManifest.validate(
                "d".repeat(64),
                Set.of("evidence-1"),
                List.of(candidate(false, "evidence-1")));
        assertEquals("CLAIM_SUPPORT_NOT_VERIFIED",
                assertInstanceOf(
                        ValidatedCitationManifest.ValidationOutcome.Rejected.class,
                        unsupported).code());

        var outside = ValidatedCitationManifest.validate(
                "d".repeat(64),
                Set.of("evidence-1"),
                List.of(candidate(true, "evidence-2")));
        assertEquals("CITATION_OUTSIDE_EVIDENCE_WHITELIST",
                assertInstanceOf(
                        ValidatedCitationManifest.ValidationOutcome.Rejected.class,
                        outside).code());
    }

    private FencedCommitOutcome committed(String type, String ref) {
        return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                TurnStatus.COMPLETED, "COMPLETED", type, ref, "{}"));
    }

    private BoundSourcePlan directPlan() {
        SourceProbeBinding probe = new SourceProbeBinding(
                TURN,
                new PlanningLineageFingerprint("a".repeat(64)),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64));
        DirectSelector selector = new DirectSelector(new DirectCandidateFact(
                probe,
                "candidate-1",
                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                "observation-1",
                "clarification-ref-1"));
        SourcePlanIdentity identity = new SourcePlanIdentity(
                probe.lineage(), "e".repeat(64));
        return new BoundSourcePlan(
                new SourceAwareDrawPlan.Direct(selector),
                identity,
                new SourceExecutionEntry.Primary());
    }

    private SourceCommitBinding binding(SourcePlanIdentity identity) {
        return new SourceCommitBinding(
                identity,
                "snapshot-1",
                "1".repeat(64),
                "2".repeat(64));
    }

    private ValidatedCitationManifest manifest() {
        return ((ValidatedCitationManifest.ValidationOutcome.Ready)
                ValidatedCitationManifest.validate(
                        "3".repeat(64),
                        Set.of("evidence-1"),
                        List.of(candidate(true, "evidence-1")))).manifest();
    }

    private ValidatedCitationManifest.CandidateCitation candidate(
            boolean supported,
            String evidenceId
    ) {
        return new ValidatedCitationManifest.CandidateCitation(
                "citation-1",
                "target-1",
                "4".repeat(64),
                supported,
                List.of(new ValidatedCitationManifest.EvidenceLink(
                        "citation-key-1",
                        evidenceId,
                        "material-1",
                        "version-1",
                        "revision-1",
                        "PROJECT")));
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                TURN,
                AttemptLease.fromDatabaseClock(
                        "attempt-1",
                        1,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"),
                        30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(
                        1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private BaseTurnContext context() {
        return new BaseTurnContext(
                new CurrentRequestContext(
                        "turn-1", "diagram-1", new CurrentInstruction("draw")),
                new AvailableContext<>(
                        new CurrentMessageAttachmentsContext("binding-1", List.of()),
                        "attachments"),
                new AbsentContext<>("no clarification"),
                new AvailableContext<>(
                        new TrustedCanvasContext(false, 0, 0, ""), "canvas"),
                new AvailableContext<>(
                        new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(
                        new ConversationContext(List.of(), ""), "conversation"),
                new AbsentContext<>("no membership"),
                new AbsentContext<>("no profile"),
                new AbsentContext<>("no memory"),
                new ContextDiagnostics(List.of()));
    }

    private ContextReadSet readSet() {
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "NO_CANVAS"),
                ContextSlicePin.absent(
                        ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(
                        ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(
                        ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private ContextReadSet pinnedCanvasReadSet() {
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.pinned(
                        ContextSlice.SUMMARY, "diagram-1", 5, "9".repeat(64)),
                ContextSlicePin.absent(
                        ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(
                        ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(
                        ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    /** Test-only dispatcher: it is deliberately absent from production composition. */
    private record IsolatedAllPathExecutor(
            DirectTurnHandler direct,
            GroundedTurnHandler grounded,
            EvidenceAnswerTurnHandler answer
    ) {
        FencedCommitOutcome direct(
                FencedAttempt attempt,
                BaseTurnContext context,
                ContextReadSet readSet,
                BoundSourcePlan plan,
                SourceCommitBinding binding,
                String artifactLease,
                String sourceIdentity,
                DirectCandidateOrigin origin,
                org.zipp.ai.application.turn.TurnEventSink events,
                CancellationSignal cancellation
        ) {
            return direct.execute(
                    attempt,
                    context,
                    readSet,
                    plan,
                    binding,
                    artifactLease,
                    sourceIdentity,
                    origin,
                    events,
                    cancellation);
        }

        FencedCommitOutcome answer(
                FencedAttempt attempt,
                BaseTurnContext context,
                ContextReadSet readSet,
                SourceCommitBinding binding,
                String evidence,
                ValidatedCitationManifest citations,
                org.zipp.ai.application.turn.TurnEventSink events
        ) {
            return answer.execute(
                    attempt, context, readSet, binding, evidence, citations, events);
        }
    }
}
