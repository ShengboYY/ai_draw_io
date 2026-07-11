package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.evaluation.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class EvalOperationsServiceTest {
    private final EvalCanaryService.Policy policy = new EvalCanaryService.Policy(100, 0.02, 1.3, 1.2, 0.1);

    @Test
    public void canaryCriticalFindingMustRecommendHalt() {
        EvalCanaryService.Decision decision = new EvalCanaryService().evaluate(
                new EvalCanaryService.Window(1000, 10, 0, 0, 100, 0.01),
                new EvalCanaryService.Window(100, 1, 1, 100, 100, 0.01), policy);
        assertEquals(EvalCanaryService.Outcome.HALT_RECOMMENDED, decision.outcome());
    }

    @Test
    public void insufficientCanaryMustProduceNoDecisionAndHealthyWindowCanContinue() {
        EvalCanaryService service = new EvalCanaryService();
        assertEquals(EvalCanaryService.Outcome.NO_DECISION, service.evaluate(null,
                new EvalCanaryService.Window(10, 0, 0, 0, 100, 0.01), policy).outcome());
        assertEquals(EvalCanaryService.Outcome.CONTINUE, service.evaluate(
                new EvalCanaryService.Window(1000, 10, 0, 0, 100, 0.01),
                new EvalCanaryService.Window(200, 2, 0, 0, 110, 0.011), policy).outcome());
    }

    @Test
    public void caseHealthShouldFlagFlakyAndPersistOnlyNonSensitiveFeedback() throws Exception {
        MemoryStore store = new MemoryStore();
        EvalCaseHealthRecord health = new EvalCaseHealthService(store).assess("case-1", "1", true, Instant.now(),
                List.of(sample(true), sample(false)));
        java.nio.file.Path report = Files.createTempDirectory("eval-operations-");
        new EvalOperationsReportWriter().write(new EvalCanaryService.Decision(EvalCanaryService.Outcome.CONTINUE, List.of()),
                List.of(health), report);

        assertEquals("FLAKY", health.getHealthStatus());
        assertSame(health, store.health);
        assertFalse(java.util.Arrays.stream(EvalCaseHealthRecord.class.getDeclaredFields())
                .anyMatch(field -> field.getName().toLowerCase().contains("run") || field.getName().toLowerCase().contains("user")));
        assertTrue(Files.exists(report.resolve("case-health.json")));
    }

    @Test
    public void unreproducibleBaselineMustMarkCaseBroken() {
        EvalCaseHealthRecord health = new EvalCaseHealthService(null).assess("case-1", "1", false, Instant.now(), List.of(sample(true)));
        assertEquals("BROKEN_BASELINE", health.getHealthStatus());
    }

    private EvalSampleResult sample(boolean passed) {
        return EvalSampleResult.builder().caseId("case-1").status(passed ? EvalHarnessResult.Status.PASS : EvalHarnessResult.Status.FAIL)
                .passed(passed).build();
    }

    private static final class MemoryStore implements ITraceToEvalStore {
        private EvalCaseHealthRecord health;
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.empty(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
        @Override public void upsertCaseHealth(EvalCaseHealthRecord value) { health = value; }
    }
}
