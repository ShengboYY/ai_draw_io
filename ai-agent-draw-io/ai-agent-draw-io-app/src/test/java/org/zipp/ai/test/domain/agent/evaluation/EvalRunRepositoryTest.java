package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.EvalRunRepository;
import org.zipp.ai.infrastructure.dao.IEvalRunMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.*;

import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

public class EvalRunRepositoryTest {
    @Test
    public void repositoryRoundTripsManifestEpisodeAndGraders() {
        FakeMapper mapper = new FakeMapper();
        EvalRunRepository repository = new EvalRunRepository(mapper);
        EvalRun run = EvalRun.builder().id("run-1").mode(EvalRunMode.MODE_B).datasetId("core")
                .datasetVersion("v1").idempotencyKey("key-1").repetitions(2).gitSha("sha")
                .maxEstimatedCost(5D).minimumCases(2).maximumErrorRate(0.1D).minimumPairedCases(2).regressionThreshold(0.02D)
                .graderManifestJson("[]").status(EvalRunStatus.QUEUED).createdBy("admin")
                .createdAt(Instant.parse("2026-07-13T06:00:00Z")).build();
        EvalEpisode episode = EvalEpisode.builder().id("ep-1").evalRunId("run-1").caseId("case")
                .caseVersion("1").repetition(0).attempt(1).status(EvalEpisodeStatus.FAIL)
                .traceRef("trace").artifactRef("trace").latencyMs(12).build();
        EvalGraderResultRecord grader = EvalGraderResultRecord.builder().episodeId("ep-1")
                .graderName("graph").graderVersion("v1").status(EvalEpisodeStatus.FAIL)
                .severity("major").evidenceJson("[]").build();
        EvalJudgeResultRecord judge = EvalJudgeResultRecord.builder().episodeId("ep-1").judgeVersion("judge-v1")
                .calibrationVersion("cal-v1").status(EvalEpisodeStatus.PASS).scoreJson("{}").evidenceJson("[]").build();
        EvalGateDecisionRecord gate = EvalGateDecisionRecord.builder().evalRunId("run-1").gateVersion("gate-v1")
                .outcome(EvalGateOutcome.PASS).reasonsJson("[]").decidedAt(Instant.parse("2026-07-13T06:01:00Z")).build();

        repository.insertRun(run); repository.saveEpisode(episode); repository.replaceGraders("ep-1", List.of(grader));
        repository.saveJudge(judge); repository.saveGate(gate);

        assertEquals("key-1", repository.findRun("run-1").orElseThrow().getIdempotencyKey());
        assertEquals(List.of("trace"), repository.findEpisode("ep-1").orElseThrow().getArtifactRefs());
        assertEquals("graph", repository.listGraders("ep-1").get(0).getGraderName());
        assertEquals(5D, repository.findRun("run-1").orElseThrow().getMaxEstimatedCost(), 0.001D);
        assertEquals("judge-v1", repository.findJudge("ep-1").orElseThrow().getJudgeVersion());
        assertEquals(EvalGateOutcome.PASS, repository.findGate("run-1").orElseThrow().getOutcome());
    }

    private static final class FakeMapper implements IEvalRunMapper {
        EvalRunPO run; EvalEpisodePO episode; EvalJudgeResultPO judge; EvalGateDecisionPO gate;
        final List<EvalGraderResultPO> graders = new ArrayList<>();
        @Override public int insertRun(EvalRunPO value) { run = value; return 1; }
        @Override public int updateRun(EvalRunPO value) { run = value; return 1; }
        @Override public EvalRunPO selectRun(String id) { return run; }
        @Override public EvalRunPO selectRunByIdempotencyKey(String key) { return run; }
        @Override public List<EvalRunPO> selectRuns(int limit, int offset) { return run == null ? List.of() : List.of(run); }
        @Override public List<EvalRunPO> selectRunsByTarget(String target, int limit, int offset) {
            return run == null || !target.equals(run.getEvaluationTarget()) ? List.of() : List.of(run);
        }
        @Override public int upsertEpisode(EvalEpisodePO value) { episode = value; return 1; }
        @Override public EvalEpisodePO selectEpisode(String id) { return episode; }
        @Override public List<EvalEpisodePO> selectEpisodes(String id) { return episode == null ? List.of() : List.of(episode); }
        @Override public int deleteGraders(String id) { graders.clear(); return 1; }
        @Override public int insertGrader(EvalGraderResultPO value) { graders.add(value); return 1; }
        @Override public List<EvalGraderResultPO> selectGraders(String id) { return graders; }
        @Override public int upsertJudge(EvalJudgeResultPO value) { judge = value; return 1; }
        @Override public EvalJudgeResultPO selectJudge(String id) { return judge; }
        @Override public int upsertGate(EvalGateDecisionPO value) { gate = value; return 1; }
        @Override public EvalGateDecisionPO selectGate(String id) { return gate; }
    }
}
