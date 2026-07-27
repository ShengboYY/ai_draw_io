package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
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
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.planning.FallbackReason;
import org.zipp.ai.application.turn.planning.OptionalEnrichmentPlanner;
import org.zipp.ai.application.turn.planning.OptionalEvidenceOutcome;
import org.zipp.ai.application.turn.planning.OptionalRetrievalDrawPlan;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.SourcePlanDecision;
import org.zipp.ai.application.turn.planning.SourceProbeCommand;
import org.zipp.ai.application.turn.planning.SourceProbeOutcome;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionalEnrichmentFallbackHandlerTest {

    @Test
    void probeFallbackStartsFreshPlainInvocationAndEmitsHonestReceipt() {
        List<String> events = new ArrayList<>();
        int[] generations = {0};
        CancellationSignal cancellation = () -> false;
        AtomicReference<CancellationSignal> observedCancellation = new AtomicReference<>();
        OptionalEnrichmentFallbackHandler handler = handler((request, sink, signal) -> {
            observedCancellation.set(signal);
            generations[0]++;
            assertEquals("draw a login flow", request.plan().instruction());
            return new PlainGenerationResult("plain-payload", "<mxGraphModel/>", "created");
        });
        SourceProbeCommand.OptionalDiscovery command = command();
        SourcePlanDecision.ProbeFallbackReady fallback = assertInstanceOf(
                SourcePlanDecision.ProbeFallbackReady.class,
                new OptionalEnrichmentPlanner().plan(
                        command,
                        new SourceProbeOutcome.Unavailable(
                                command.binding(), SourceProbeOutcome.Unavailability.NO_MATCH)));

        handler.executeProbeFallback(
                attempt(), context(), readSet(), fallback,
                event -> events.add(event.type() + ":" + event.payload()),
                cancellation);

        assertSame(cancellation, observedCancellation.get());
        assertEquals(1, generations[0]);
        assertEquals("enrichment_skipped", events.get(0).split(":")[0]);
        assertFalse(events.get(0).contains("source_used"));
        assertEquals("plain_started", events.get(1).split(":")[0]);
        assertEquals("plain_committed", events.get(2).split(":")[0]);
    }

    @ParameterizedTest
    @EnumSource(value = FallbackReason.class, names = {
            "SNAPSHOT_DEPENDENCY_UNAVAILABLE",
            "EVIDENCE_INSUFFICIENT",
            "RETRIEVAL_DEPENDENCY_FAILURE",
            "GROUNDED_CITATION_REJECTED_BEFORE_COMMIT"
    })
    void postProbeFallbackDestroysPrimaryScopeBeforePlainGeneration(FallbackReason reason) {
        List<String> order = new ArrayList<>();
        OptionalEnrichmentFallbackHandler handler = handler((request, sink, cancellation) -> {
            order.add("generate");
            return new PlainGenerationResult("plain-payload", "<mxGraphModel/>", "created");
        });
        TrackingScope scope = new TrackingScope(order);

        handler.executeEvidenceFallback(
                attempt(), context(), readSet(), optionalPlan(),
                new OptionalEvidenceOutcome.FallbackEligible(reason),
                scope,
                event -> order.add(event.type()));

        assertEquals(List.of(
                "discard", "close", "enrichment_skipped",
                "plain_started", "generate", "plain_committed"), order);
    }

    @Test
    void primaryScopeCloseFailurePreventsFallbackInvocation() {
        int[] generations = {0};
        OptionalEnrichmentFallbackHandler handler = handler((request, sink, cancellation) -> {
            generations[0]++;
            return new PlainGenerationResult("plain-payload", "<mxGraphModel/>", "created");
        });
        OptionalPrimaryBranchScope scope = new OptionalPrimaryBranchScope() {
            @Override
            public void discard() {
            }

            @Override
            public void close() {
                throw new IllegalStateException("scope did not close");
            }
        };

        assertThrows(IllegalStateException.class, () -> handler.executeEvidenceFallback(
                attempt(), context(), readSet(), optionalPlan(),
                new OptionalEvidenceOutcome.FallbackEligible(
                        FallbackReason.EVIDENCE_INSUFFICIENT),
                scope,
                event -> { }));
        assertEquals(0, generations[0]);
    }

    @Test
    void discardFailureStillClosesPrimaryScopeAndPreventsFallbackInvocation() {
        List<String> order = new ArrayList<>();
        OptionalEnrichmentFallbackHandler handler = handler((request, sink, cancellation) -> {
            order.add("generate");
            return new PlainGenerationResult("plain-payload", "<mxGraphModel/>", "created");
        });
        OptionalPrimaryBranchScope scope = new OptionalPrimaryBranchScope() {
            @Override
            public void discard() {
                order.add("discard");
                throw new IllegalStateException("discard failed");
            }

            @Override
            public void close() {
                order.add("close");
            }
        };

        assertThrows(IllegalStateException.class, () -> handler.executeEvidenceFallback(
                attempt(), context(), readSet(), optionalPlan(),
                new OptionalEvidenceOutcome.FallbackEligible(
                        FallbackReason.RETRIEVAL_DEPENDENCY_FAILURE),
                scope,
                event -> { }));
        assertEquals(List.of("discard", "close"), order);
    }

    private OptionalEnrichmentFallbackHandler handler(PlainGenerationPort generation) {
        PlainDrawingHandler plain = new PlainDrawingHandler(
                generation,
                command -> new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                        TurnStatus.COMPLETED, "COMPLETED", "plain",
                        command.payloadRef(), "{}")),
                new PlainRuntimeRegistry(),
                PlainExecutionProfile.m2SourceFree());
        return new OptionalEnrichmentFallbackHandler(plain);
    }

    private SourceProbeCommand.OptionalDiscovery command() {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                SourceDemandKind.OPTIONAL_DISCOVERY, List.of(), "login architecture");
        return (SourceProbeCommand.OptionalDiscovery) SourceProbeCommand.from(
                new PrePlanOutcome.SourcePlanningRequired(
                        new TurnKey("owner-1", "conversation-1", "turn-1"),
                        new CurrentInstruction("draw a login flow"),
                        new SemanticIntent(
                                SemanticAction.CREATE,
                                OutputIntent.DRAWING,
                                TargetNeed.NOT_REQUIRED,
                                "flowchart",
                                "none"),
                        new ResolvedSourceDemand(accepted, List.of()),
                        accepted,
                        new PlanningLineageFingerprint("c".repeat(64)),
                        "a".repeat(64),
                        "b".repeat(64)));
    }

    private OptionalRetrievalDrawPlan optionalPlan() {
        SourceProbeCommand.OptionalDiscovery command = command();
        return new OptionalRetrievalDrawPlan(
                List.of("source-v1"),
                command.validatedProbeFallback(),
                command.binding().lineage());
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                AttemptLease.fromDatabaseClock(
                        "attempt-1", 1, Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private BaseTurnContext context() {
        return new BaseTurnContext(
                new CurrentRequestContext(
                        "turn-1", "diagram-1", new CurrentInstruction("draw a login flow")),
                new AvailableContext<>(
                        new CurrentMessageAttachmentsContext("binding-1", List.of()), "attachments"),
                new AbsentContext<>("no clarification"),
                new AvailableContext<>(new TrustedCanvasContext(false, 0, 0, ""), "canvas"),
                new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(new ConversationContext(List.of(), ""), "conversation"),
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
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private static final class TrackingScope implements OptionalPrimaryBranchScope {

        private final List<String> order;

        private TrackingScope(List<String> order) {
            this.order = order;
        }

        @Override
        public void discard() {
            order.add("discard");
        }

        @Override
        public void close() {
            order.add("close");
        }
    }
}
