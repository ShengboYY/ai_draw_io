package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.classification.SemanticRouterInput;
import org.zipp.ai.application.turn.classification.SemanticRouterPromptRenderer;
import org.zipp.ai.application.turn.demand.AttachmentCandidateOrigin;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseTurnContextBoundaryTest {

    @Test
    void routerProjectionIncludesContextAndOnlyOpaqueSourceFacts() {
        BaseTurnContext base = base();

        RestrictedSourceDemandInput source =
                new DefaultRestrictedSourceDemandInputFactory().create(base);
        SemanticRouterInput input =
                new DefaultSemanticRouterContextProjector().forRouter(base)
                        .withSourceContext(source);
        assertEquals("draw a flowchart", input.instruction().value());
        assertTrue(input.context().canvasAvailable());
        assertEquals("Use blue lanes", input.context().profileInstructions());
        assertEquals(List.of("Prefer short labels"), input.context().autoMemoryEntries());
        assertEquals(List.of(new OpaqueConversationFileRef("file-1")),
                input.currentMessageAttachments());

        String prompt = new SemanticRouterPromptRenderer().render(input);
        assertTrue(prompt.contains("CHARTBOOK_PROFILE_DATA"));
        assertTrue(prompt.contains("AUTO_MEMORY_DATA"));
        assertTrue(prompt.contains("ELIGIBLE_ATTACHMENT_CANDIDATES_DATA"));
        assertTrue(prompt.contains("file-1"));
        assertFalse(prompt.contains("SOURCE_AVAILABILITY"));
        assertFalse(prompt.contains("EVIDENCE_DATA"));
    }

    @Test
    void sourcePolicyProjectionContainsOnlyOpaqueCurrentTurnFacts() {
        BaseTurnContext base = base();

        RestrictedSourceDemandInput input = new DefaultRestrictedSourceDemandInputFactory().create(base);
        assertEquals(List.of(new OpaqueConversationFileRef("file-1")), input.currentMessageAttachments());
        assertEquals("chartbook-1", input.chartbookMembership().orElseThrow());
        assertEquals(Set.of("first", "second"), input.activeClarificationLabels());
    }

    @Test
    void recentUserMessageAttachmentIsEligibleWhenTheCurrentMessageHasNone() {
        BaseTurnContext base = baseWithAttachments(
                List.of(),
                List.of(new ConversationAttachmentView(
                        new OpaqueConversationFileRef("prior-image"),
                        "image/png",
                        "reference.png")));

        RestrictedSourceDemandInput input =
                new DefaultRestrictedSourceDemandInputFactory().create(base);

        assertEquals(List.of(new OpaqueConversationFileRef("prior-image")),
                input.eligibleAttachmentRefs());
        assertEquals(AttachmentCandidateOrigin.RECENT_USER_MESSAGE,
                input.attachmentCandidates().get(0).origin());
        String prompt = new SemanticRouterPromptRenderer().render(
                new DefaultSemanticRouterContextProjector().forRouter(base)
                        .withSourceContext(input));
        assertTrue(prompt.contains("origin=RECENT_USER_MESSAGE"));
        assertTrue(prompt.contains("mediaType=image/png"));
        assertTrue(prompt.contains("displayName=reference.png"));
    }

    @Test
    void currentMessageAttachmentHidesRecentMessageAttachments() {
        BaseTurnContext base = baseWithAttachments(
                List.of(new CurrentMessageAttachmentView(
                        new OpaqueConversationFileRef("current-pdf"),
                        "application/pdf",
                        "report.pdf")),
                List.of(new ConversationAttachmentView(
                        new OpaqueConversationFileRef("prior-image"),
                        "image/png",
                        "reference.png")));

        RestrictedSourceDemandInput input =
                new DefaultRestrictedSourceDemandInputFactory().create(base);

        assertEquals(List.of(new OpaqueConversationFileRef("current-pdf")),
                input.eligibleAttachmentRefs());
        assertEquals(AttachmentCandidateOrigin.CURRENT_MESSAGE,
                input.attachmentCandidates().get(0).origin());
        assertEquals("report.pdf", input.attachmentCandidates().get(0).displayName());
    }

    @Test
    void degradedProfileAndMembershipAreNotInjectedAsLiveFacts() {
        BaseTurnContext base = new BaseTurnContext(
                new CurrentRequestContext("turn-1", "diagram-1", new CurrentInstruction("draw")),
                new AvailableContext<>(new CurrentMessageAttachmentsContext(
                        "binding-1", List.of()), "message-binding"),
                new AvailableContext<>(new ActiveClarificationContext(Set.of()), "clarification"),
                new AvailableContext<>(new TrustedCanvasContext(false, 0, 0, ""), "canvas"),
                new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(new ConversationContext(List.of(), ""), "conversation"),
                new DegradedContext<>("membership unavailable"),
                new DegradedContext<>("profile skipped"),
                new DegradedContext<>("memory skipped"),
                new ContextDiagnostics(List.of("CONTEXT_DEGRADED")));

        SemanticRouterInput router = new DefaultSemanticRouterContextProjector().forRouter(base);
        RestrictedSourceDemandInput demand = new DefaultRestrictedSourceDemandInputFactory().create(base);
        assertFalse(router.context().profileAvailable());
        assertFalse(router.context().memoryAvailable());
        assertEquals("", router.context().chartbookMembership());
        assertTrue(demand.chartbookMembership().isEmpty());
    }

    private BaseTurnContext base() {
        return baseWithAttachments(
                List.of(new CurrentMessageAttachmentView(
                        new OpaqueConversationFileRef("file-1"),
                        "application/pdf",
                        "spec.pdf")),
                List.of());
    }

    private BaseTurnContext baseWithAttachments(
            List<CurrentMessageAttachmentView> currentAttachments,
            List<ConversationAttachmentView> recentAttachments
    ) {
        return new BaseTurnContext(
                new CurrentRequestContext("turn-1", "diagram-1", new CurrentInstruction("draw a flowchart")),
                new AvailableContext<>(new CurrentMessageAttachmentsContext(
                        "binding-1", currentAttachments),
                        "message-binding"),
                new AvailableContext<>(new ActiveClarificationContext(Set.of("first", "second")), "clarification"),
                new AvailableContext<>(new TrustedCanvasContext(true, 3, 2, "three nodes and two edges"), "canvas"),
                new AvailableContext<>(new ValidatedSelectionContext(true, 1), "selection"),
                new AvailableContext<>(new ConversationContext(
                        List.of("previous turn"), "short history", recentAttachments), "conversation"),
                new AvailableContext<>(new ChartbookMembershipContext("chartbook-1", 2, 3), "membership"),
                new AvailableContext<>(new ChartbookProfileContext(
                        "Use blue lanes", "", "", List.of("flow"), "clean"), "profile"),
                new AvailableContext<>(new AutoMemoryContext(
                        List.of("Prefer short labels"), List.of()), "memory"),
                new ContextDiagnostics(List.of()));
    }
}
