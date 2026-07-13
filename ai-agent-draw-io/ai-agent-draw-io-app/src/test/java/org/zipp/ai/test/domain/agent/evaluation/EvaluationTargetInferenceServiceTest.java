package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationTargetInference;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvaluationTargetInferenceService;

import java.util.List;

import static org.junit.Assert.*;

public class EvaluationTargetInferenceServiceTest {
    private final EvaluationTargetInferenceService service = new EvaluationTargetInferenceService();

    @Test
    public void explicitTargetIsConfirmedAndLegacyFixtureIsInferred() {
        EvaluationTargetInference explicit = service.infer(EvalCaseDefinition.builder()
                .evaluationTarget(EvaluationTarget.INTENT_ROUTER).build());
        EvaluationTargetInference legacy = service.infer(EvalCaseDefinition.builder()
                .fixtureVersion("fixture-v1").build());

        assertEquals(EvaluationTarget.INTENT_ROUTER, explicit.target());
        assertEquals(EvaluationTargetMigrationStatus.CONFIRMED, explicit.status());
        assertEquals(EvaluationTarget.FULL_AGENT, legacy.target());
        assertEquals(EvaluationTargetMigrationStatus.INFERRED, legacy.status());
    }

    @Test
    public void uncertainAndOverlappingLegacySignalsRemainAmbiguous() {
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType("edit_existing");
        expected.setGraph(new EvalCaseDefinition.GraphAssertions());

        EvaluationTargetInference missing = service.infer(EvalCaseDefinition.builder().tags(List.of("legacy")).build());
        EvaluationTargetInference overlapping = service.infer(EvalCaseDefinition.builder().expected(expected).build());
        EvaluationTargetInference conflictingTags = service.infer(EvalCaseDefinition.builder()
                .tags(List.of("target:full_agent", "target:intent_router")).build());

        assertNull(missing.target());
        assertEquals(EvaluationTargetMigrationStatus.AMBIGUOUS, missing.status());
        assertNull(overlapping.target());
        assertEquals(EvaluationTargetMigrationStatus.AMBIGUOUS, overlapping.status());
        assertNull(conflictingTags.target());
        assertEquals(EvaluationTargetMigrationStatus.AMBIGUOUS, conflictingTags.status());
    }
}
