package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSourcePlannerTest {

    private final TaskSourcePlanner planner = new DefaultTaskSourcePlanner();

    @Test
    void plainTextDrawingDoesNotDependOnMaterialCapabilities() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.NONE, List.of(), List.of(), List.of(), "", List.of()));

        assertEquals(SourceUse.NONE, plan.sourceUse());
        assertFalse(plan.requiresVisualObservation());
        assertFalse(plan.strict());
    }

    @Test
    void oneNewlyUploadedImageWinsAmongOtherAuthorizedCandidates() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT, List.of("ver-conversation", "ver-chartbook"),
                List.of("ver-conversation"), List.of("ver-conversation"), "", List.of()));

        assertEquals(SourceUse.DIRECT, plan.sourceUse());
        assertEquals("ver-conversation", plan.primaryDirectVersionId());
        assertTrue(plan.requiresVisualObservation());
        assertFalse(plan.needsClarification());
    }

    @Test
    void uniquelyNamedChartbookImageBecomesThePrimaryDirectSource() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT, List.of("ver-conversation", "ver-chartbook"),
                List.of(), List.of("ver-conversation"), "ver-chartbook", List.of()));

        assertEquals(SourceUse.DIRECT, plan.sourceUse());
        assertEquals("ver-chartbook", plan.primaryDirectVersionId());
        assertFalse(plan.needsClarification());
    }

    @Test
    void soleReadyConversationImageCanBeReusedOnALaterTurn() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT, List.of("ver-conversation"), List.of(),
                List.of("ver-conversation"), "", List.of()));

        assertEquals("ver-conversation", plan.primaryDirectVersionId());
        assertTrue(plan.requiresVisualObservation());
    }

    @Test
    void soleConversationImageWinsOverAnUnnamedChartbookImage() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT, List.of("ver-conversation", "ver-chartbook"), List.of(),
                List.of("ver-conversation"), "", List.of()));

        assertEquals("ver-conversation", plan.primaryDirectVersionId());
        assertFalse(plan.needsClarification());
    }

    @Test
    void unnamedChartbookImageRequiresClarificationEvenWhenItIsTheOnlyCandidate() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT, List.of("ver-chartbook"), List.of(), List.of(), "", List.of()));

        assertEquals("", plan.primaryDirectVersionId());
        assertTrue(plan.needsClarification());
    }

    @Test
    void multipleUnqualifiedImagesRequireClarificationInsteadOfRandomSelection() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT, List.of("ver-a", "ver-b"), List.of(), List.of(), "", List.of()));

        assertEquals(SourceUse.DIRECT, plan.sourceUse());
        assertEquals("", plan.primaryDirectVersionId());
        assertTrue(plan.needsClarification());
        assertEquals("AMBIGUOUS_DIRECT_IMAGE", plan.clarificationReason());
        assertFalse(plan.requiresVisualObservation());
    }

    @Test
    void imageReconstructionWithReferenceFactsUsesBothSources() {
        TaskSourcePlan plan = planner.plan(command(
                SourceUse.DIRECT_AND_RETRIEVAL, List.of("ver-image"), List.of(),
                List.of("ver-image"), "", List.of("ver-guide")));

        assertEquals(SourceUse.DIRECT_AND_RETRIEVAL, plan.sourceUse());
        assertEquals("ver-image", plan.primaryDirectVersionId());
        assertEquals(List.of("ver-guide"), plan.selectedReferenceVersionIds());
        assertTrue(plan.requiresVisualObservation());
    }

    @Test
    void explicitOnlyNeverExpandsTheSelectedReferenceScope() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.RETRIEVAL, SourceMode.EXPLICIT_ONLY, List.of(), List.of(), List.of(), "",
                List.of("ver-guide"), 0));

        assertEquals(SourceUse.RETRIEVAL, plan.sourceUse());
        assertEquals(SourceMode.EXPLICIT_ONLY, plan.retrievalMode());
        assertTrue(plan.strict());
    }

    @Test
    void noMaterialModeOverridesARequestedRetrievalPlan() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.RETRIEVAL, SourceMode.NONE, List.of(), List.of(), List.of(), "",
                List.of("ver-guide"), 0));

        assertEquals(SourceUse.NONE, plan.sourceUse());
        assertEquals(SourceMode.NONE, plan.retrievalMode());
        assertEquals(List.of(), plan.selectedReferenceVersionIds());
    }

    @Test
    void layoutOptimizationNeverReadsAnySource() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.OPTIMIZE_LAYOUT,
                SourceUse.DIRECT_AND_RETRIEVAL, SourceMode.AUTO, List.of("ver-image"),
                List.of("ver-image"), List.of("ver-image"), "ver-image", List.of("ver-guide"), 0));

        assertEquals(SourceUse.NONE, plan.sourceUse());
        assertEquals("", plan.primaryDirectVersionId());
        assertFalse(plan.requiresVisualObservation());
    }

    private TaskSourcePlanningCommand command(SourceUse use, List<String> candidates,
                                              List<String> newlyUploaded, List<String> conversation,
                                              String named,
                                              List<String> references) {
        return new TaskSourcePlanningCommand(CanvasAction.CREATE, use, SourceMode.AUTO,
                candidates, newlyUploaded, conversation, named, references, 0);
    }
}
