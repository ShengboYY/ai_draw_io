package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticMinerRunStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.SemanticSamplingPolicy;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.TraceToEvalRepository;
import org.zipp.ai.infrastructure.dao.ITraceToEvalMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SemanticMinerRepositoryTest {

    @Test
    public void shouldRoundTripCandidateModelEvidenceAndMinerRun() throws Exception {
        FakeMapper mapper = new FakeMapper();
        TraceToEvalRepository repository = new TraceToEvalRepository();
        Field field = TraceToEvalRepository.class.getDeclaredField("mapper");
        field.setAccessible(true);
        field.set(repository, mapper);
        Instant now = Instant.parse("2026-07-13T08:00:00Z");
        EvalCaseCandidate candidate = EvalCaseCandidate.builder().id("candidate-1").sourceRunId("run-1")
                .failureFamily("semantic_false_success").ruleId("semantic_miner").evidenceSummary("loading failed")
                .risk("high").discoveredAt(now).policyVersion("semantic-sampling-v1")
                .status(EvalCandidateStatus.DETECTED).createdBy("semantic-miner").detectionSource("MODEL_DETECTED")
                .modelVersion("model-v1").modelConfidence(0.93D).modelEvidence(List.of("loading failed")).build();
        SemanticMinerRun run = SemanticMinerRun.builder().id("scan-1").status(SemanticMinerRunStatus.COMPLETED)
                .samplingPolicy(SemanticSamplingPolicy.TARGETED).requestedLimit(20).sampledCount(5).analyzedCount(4)
                .candidateCount(1).errorCount(1).estimatedCostUsd(0.01D).modelVersion("model-v1")
                .sanitizerVersion("sanitizer-v1").createdBy("admin-1").createdAt(now).completedAt(now).build();

        repository.insertCandidate(candidate);
        repository.insertSemanticMinerRun(run);

        EvalCaseCandidate restoredCandidate = repository.findCandidate("candidate-1").orElseThrow();
        SemanticMinerRun restoredRun = repository.findSemanticMinerRun("scan-1").orElseThrow();
        assertEquals("MODEL_DETECTED", restoredCandidate.getDetectionSource());
        assertEquals(List.of("loading failed"), restoredCandidate.getModelEvidence());
        assertEquals(SemanticMinerRunStatus.COMPLETED, restoredRun.getStatus());
        assertEquals(Integer.valueOf(1), restoredRun.getCandidateCount());
    }

    private static final class FakeMapper implements ITraceToEvalMapper {
        private EvalCaseCandidatePO candidate;
        private SemanticMinerRunPO run;
        @Override public EvalCaseCandidatePO selectCandidate(String candidateId) { return candidate; }
        @Override public EvalCaseCandidatePO selectCandidateBySource(String sourceRunId, String failureFamily) { return candidate; }
        @Override public List<EvalCaseCandidatePO> selectCandidates(String status, String risk, int limit, int offset) { return candidate == null ? List.of() : List.of(candidate); }
        @Override public int insertCandidate(EvalCaseCandidatePO candidate) { this.candidate = candidate; return 1; }
        @Override public int updateCandidateStatus(String candidateId, String status) { candidate.setStatus(status); return 1; }
        @Override public int mergeCandidateModelEvidence(String candidateId, String modelVersion, Double modelConfidence, String modelEvidenceJson, String evidenceSummary) { return 1; }
        @Override public int insertReview(EvalCaseReviewPO review) { return 1; }
        @Override public int insertLineage(EvalCaseLineagePO lineage) { return 1; }
        @Override public int insertDraft(EvalCaseDraftPO draft) { return 1; }
        @Override public EvalCaseDraftPO selectLatestDraft(String candidateId) { return null; }
        @Override public int upsertCaseHealth(EvalCaseHealthPO health) { return 1; }
        @Override public List<EvalCaseHealthPO> selectCaseHealth(String status, int limit) { return List.of(); }
        @Override public int insertSemanticMinerRun(SemanticMinerRunPO run) { this.run = run; return 1; }
        @Override public int updateSemanticMinerRun(SemanticMinerRunPO run) { this.run = run; return 1; }
        @Override public SemanticMinerRunPO selectSemanticMinerRun(String runId) { return run; }
        @Override public List<SemanticMinerRunPO> selectSemanticMinerRuns(int limit) { return run == null ? List.of() : List.of(run); }
    }
}
