package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTargetMigrationStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationTargetInference;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvaluationTargetInferenceService;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    public void migrationAndDomainUseTheSameDrawingOnlySignals() throws Exception {
        EvalCaseDefinition.Expected quality = new EvalCaseDefinition.Expected();
        quality.setNeedsCanvasQuality(true);
        EvalCaseDefinition.Expected critical = new EvalCaseDefinition.Expected();
        critical.setMaxCriticalIssues(0);
        EvalCaseDefinition.Expected major = new EvalCaseDefinition.Expected();
        major.setMaxMajorIssues(1);

        for (EvalCaseDefinition.Expected expected : List.of(quality, critical, major)) {
            EvaluationTargetInference inference = service.infer(EvalCaseDefinition.builder().expected(expected).build());
            assertEquals(EvaluationTarget.DRAWING_QUALITY, inference.target());
            assertEquals(EvaluationTargetMigrationStatus.INFERRED, inference.status());
        }

        // The SQL backfill must stay in lockstep with the runtime inference contract.
        Path migration = Path.of("docs/sql/migrations/2026-07-13-add-evaluation-targets.sql");
        if (!Files.exists(migration)) migration = Path.of("../docs/sql/migrations/2026-07-13-add-evaluation-targets.sql");
        String sql = Files.readString(migration);
        assertTrue(sql.contains("$.expected.needsCanvasQuality"));
        assertTrue(sql.contains("$.expected.maxCriticalIssues"));
        assertTrue(sql.contains("$.expected.maxMajorIssues"));
        assertTrue(sql.contains("COALESCE("));
        assertTrue(sql.contains("drawing_present"));
    }
}
