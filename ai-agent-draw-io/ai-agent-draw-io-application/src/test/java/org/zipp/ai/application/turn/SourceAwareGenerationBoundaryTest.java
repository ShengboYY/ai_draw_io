package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.planning.ClarificationReplyResolutionPort;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;
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

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SourceAwareGenerationBoundaryTest {

    @Test
    void pathSpecificPortsExposeNoDynamicToolRegistry() {
        List<Class<?>> ports = List.of(
                DirectVisionPort.class,
                DirectGenerationPort.class,
                GroundedGenerationPort.class,
                EvidenceAnswerGenerationPort.class);

        for (Class<?> port : ports) {
            String signature = Arrays.toString(port.getDeclaredMethods()).toLowerCase(Locale.ROOT);
            assertFalse(signature.contains("toolregistry"), port.getSimpleName());
            assertFalse(signature.contains("agentruntime"), port.getSimpleName());
            assertFalse(signature.contains("sourceprobeport"), port.getSimpleName());
            assertFalse(signature.contains("materialrepository"), port.getSimpleName());
        }
    }

    @Test
    void clarificationReplyCarriesHiddenIdAndNaturalLanguageProposalNotCandidateRefs() {
        assertEquals(List.of("clarificationId"),
                Arrays.stream(ReplyToClarification.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
        assertEquals(List.of("optionId", "optionSetDigest"),
                Arrays.stream(ClarificationReplyResolutionPort.SelectionProposal.class
                                .getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList());
    }

    @Test
    void evidenceAnswerCannotEnableModelKnowledge() {
        EvidenceAnswerGenerationPort.Request request =
                new EvidenceAnswerGenerationPort.Request(
                        attempt(),
                        context(),
                        readSet(),
                        new SourcePlanIdentity(
                                new PlanningLineageFingerprint("c".repeat(64)),
                                "d".repeat(64)),
                        "evidence-1");

        assertFalse(request.aiKnowledgeAllowed());
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
                new CurrentRequestContext("turn-1", "diagram-1", new CurrentInstruction("answer")),
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
}
