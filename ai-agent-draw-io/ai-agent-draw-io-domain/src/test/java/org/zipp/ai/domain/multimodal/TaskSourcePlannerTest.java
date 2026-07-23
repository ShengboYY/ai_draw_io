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
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.NONE, SourceMode.AUTO, List.of(), List.of(), 0, false));

        assertEquals(SourceUse.NONE, plan.sourceUse());
        assertFalse(plan.requiresVisualObservation());
        assertFalse(plan.strict());
    }

    @Test
    void singleReadyImageReconstructionUsesDirectSourceOnly() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.DIRECT, SourceMode.AUTO, List.of("ver-image"), List.of(), 0, true));

        assertEquals(SourceUse.DIRECT, plan.sourceUse());
        assertEquals(List.of("ver-image"), plan.directAttachmentVersionIds());
        assertTrue(plan.requiresVisualObservation());
        assertFalse(plan.strict());
    }

    @Test
    void imageReconstructionWithSelectedReferenceUsesBothSources() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.DIRECT_AND_RETRIEVAL, SourceMode.EXPLICIT, List.of("ver-image"),
                List.of("ver-guide"), 0, true));

        assertEquals(SourceUse.DIRECT_AND_RETRIEVAL, plan.sourceUse());
        assertEquals(List.of("ver-image"), plan.directAttachmentVersionIds());
        assertEquals(List.of("ver-guide"), plan.selectedReferenceVersionIds());
        assertTrue(plan.requiresVisualObservation());
    }

    @Test
    void explicitOnlyNeverExpandsTheSelectedReferenceScope() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.RETRIEVAL, SourceMode.EXPLICIT_ONLY, List.of(), List.of("ver-guide"), 0, false));

        assertEquals(SourceUse.RETRIEVAL, plan.sourceUse());
        assertEquals(SourceMode.EXPLICIT_ONLY, plan.retrievalMode());
        assertTrue(plan.strict());
    }

    @Test
    void noMaterialModeOverridesARequestedRetrievalPlan() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.CREATE,
                SourceUse.RETRIEVAL, SourceMode.NONE, List.of(), List.of("ver-guide"), 0, false));

        assertEquals(SourceUse.NONE, plan.sourceUse());
        assertEquals(SourceMode.NONE, plan.retrievalMode());
        assertEquals(List.of(), plan.selectedReferenceVersionIds());
    }

    @Test
    void reviewNeverTriggersDirectImageObservation() {
        TaskSourcePlan plan = planner.plan(new TaskSourcePlanningCommand(CanvasAction.REVIEW,
                SourceUse.DIRECT, SourceMode.AUTO, List.of("ver-image"), List.of(), 0, true));

        assertEquals(SourceUse.NONE, plan.sourceUse());
        assertFalse(plan.requiresVisualObservation());
    }
}
