package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.*;
import org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.Assert.*;

public class EvalContinuousOperationsTest {
    @Test
    public void eligibleGateProducesPersistedRecommendationButNeverDeploymentAction() {
        RunStore runs = new RunStore(); runs.insertRun(run("release", null));
        runs.saveGate(EvalGateDecisionRecord.builder().evalRunId("release").outcome(EvalGateOutcome.PASS).build());
        CanaryStore store = new CanaryStore();
        EvalCanaryOperationsService service = new EvalCanaryOperationsService(runs, store,
                Clock.fixed(Instant.parse("2026-07-13T00:00:00Z"), ZoneOffset.UTC));

        EvalCanaryAssessment result = service.assess("release", "deploy-17", "policy-v1",
                new EvalCanaryService.Window(1000, 10, 0, 0, 100, 0.01),
                new EvalCanaryService.Window(100, 1, 1, 90, 100, 0.01),
                new EvalCanaryService.Policy(100, 0.02, 1.2, 1.2, 0.05), "release-owner");

        assertEquals(EvalCanaryService.Outcome.HALT_RECOMMENDED, result.getOutcome());
        assertSame(result, store.values.get(0));
        assertFalse(Arrays.stream(EvalCanaryOperationsService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().toLowerCase().contains("rollback") || method.getName().toLowerCase().contains("deploy")));
    }

    @Test
    public void noDecisionGateCannotStartCanaryAssessment() {
        RunStore runs = new RunStore(); runs.insertRun(run("release", null));
        runs.saveGate(EvalGateDecisionRecord.builder().evalRunId("release").outcome(EvalGateOutcome.NO_DECISION).build());
        EvalCanaryOperationsService service = new EvalCanaryOperationsService(runs, new CanaryStore());
        assertThrows(IllegalStateException.class, () -> service.assess("release", "deploy", "policy",
                null, new EvalCanaryService.Window(100, 0, 0, 0, 10, 0.01),
                new EvalCanaryService.Policy(100, 0.01, 1.2, 1.2, 0.1), "owner"));
    }

    @Test
    public void highInfrastructureErrorProducesNoDecisionAndInvalidWindowsAreRejected() {
        RunStore runs = new RunStore(); runs.insertRun(run("release", null));
        runs.saveGate(EvalGateDecisionRecord.builder().evalRunId("release").outcome(EvalGateOutcome.PASS).build());
        EvalCanaryOperationsService service = new EvalCanaryOperationsService(runs, new CanaryStore());
        EvalCanaryService.Policy policy = new EvalCanaryService.Policy(100, 0.01, 1.2, 1.2, 0.05);

        EvalCanaryAssessment result = service.assess("release", "deploy", "policy", null,
                new EvalCanaryService.Window(100, 0, 0, 10, 10, 0.01), policy, "owner");

        assertEquals(EvalCanaryService.Outcome.NO_DECISION, result.getOutcome());
        assertThrows(IllegalArgumentException.class, () -> service.assess("release", "deploy", "policy", null,
                new EvalCanaryService.Window(10, 11, 0, 0, 10, 0.01), policy, "owner"));
    }

    @Test
    public void nightlyHealthSeparatesBaselineReproductionFromCandidateStability() {
        RunStore runs = new RunStore(); runs.insertRun(run("baseline", null)); runs.insertRun(run("candidate", "baseline"));
        runs.saveEpisode(episode("baseline-ep", "baseline", EvalEpisodeStatus.FAIL, 0));
        for (int repetition = 0; repetition < 5; repetition++) runs.saveEpisode(episode("candidate-" + repetition, "candidate", EvalEpisodeStatus.PASS, repetition));
        HealthStore health = new HealthStore(); VersionStore versions = new VersionStore();
        versions.value = EvalCaseVersion.builder().caseId("case-1").caseVersion("1").publishedAt(Instant.now()).build();

        List<EvalCaseHealthRecord> records = new EvalCaseHealthOperationsService(runs, versions, health).refresh(20);

        assertEquals(1, records.size()); assertEquals(Boolean.TRUE, records.get(0).getBaselineReproduced());
        assertEquals("ALWAYS_PASS_REVIEW", records.get(0).getHealthStatus());
        assertFalse(Arrays.stream(EvalCaseHealthRecord.class.getDeclaredFields()).anyMatch(field -> field.getName().matches("(?i).*(run|user|payload|trace).*")));
    }

    @Test
    public void caseHealthKeepsPublishedVersionsIndependent() {
        RunStore runs = new RunStore(); runs.insertRun(run("candidate", null));
        runs.saveEpisode(episode("v1", "candidate", "1", EvalEpisodeStatus.PASS, 0));
        runs.saveEpisode(episode("v2", "candidate", "2", EvalEpisodeStatus.FAIL, 0));
        HealthStore health = new HealthStore();

        List<EvalCaseHealthRecord> records = new EvalCaseHealthOperationsService(runs, new VersionStore(), health).refresh(20);

        assertEquals(2, records.size());
        assertEquals(Set.of("1", "2"), records.stream().map(EvalCaseHealthRecord::getCaseVersion).collect(java.util.stream.Collectors.toSet()));
    }

    private EvalRun run(String id, String baseline) { return EvalRun.builder().id(id).mode(EvalRunMode.RELEASE).datasetId("core").datasetVersion("v1").gitSha("sha").repetitions(5).status(EvalRunStatus.COMPLETED).baselineRef(baseline).build(); }
    private EvalEpisode episode(String id, String run, EvalEpisodeStatus status, int repetition) { return episode(id, run, "1", status, repetition); }
    private EvalEpisode episode(String id, String run, String version, EvalEpisodeStatus status, int repetition) { return EvalEpisode.builder().id(id).evalRunId(run).caseId("case-1").caseVersion(version).status(status).repetition(repetition).build(); }

    private static final class CanaryStore implements IEvalCanaryAssessmentStore {
        final List<EvalCanaryAssessment> values = new ArrayList<>();
        @Override public void insert(EvalCanaryAssessment value) { values.add(value); }
        @Override public List<EvalCanaryAssessment> list(String run, int limit) { return values.stream().limit(limit).toList(); }
    }
    private static final class RunStore implements IEvalRunStore {
        final Map<String, EvalRun> runs = new LinkedHashMap<>(); final Map<String, EvalEpisode> episodes = new LinkedHashMap<>(); final Map<String, EvalGateDecisionRecord> gates = new HashMap<>();
        @Override public void insertRun(EvalRun run) { runs.put(run.getId(), run); }
        @Override public void updateRun(EvalRun run) { runs.put(run.getId(), run); }
        @Override public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(runs.get(id)); }
        @Override public Optional<EvalRun> findByIdempotencyKey(String key) { return Optional.empty(); }
        @Override public List<EvalRun> listRuns(int limit, int offset) { return runs.values().stream().skip(offset).limit(limit).toList(); }
        @Override public void saveEpisode(EvalEpisode episode) { episodes.put(episode.getId(), episode); }
        @Override public Optional<EvalEpisode> findEpisode(String id) { return Optional.ofNullable(episodes.get(id)); }
        @Override public List<EvalEpisode> listEpisodes(String run) { return episodes.values().stream().filter(value -> run.equals(value.getEvalRunId())).toList(); }
        @Override public void replaceGraders(String id, List<EvalGraderResultRecord> graders) { }
        @Override public List<EvalGraderResultRecord> listGraders(String id) { return List.of(); }
        @Override public void saveGate(EvalGateDecisionRecord gate) { gates.put(gate.getEvalRunId(), gate); }
        @Override public Optional<EvalGateDecisionRecord> findGate(String run) { return Optional.ofNullable(gates.get(run)); }
    }
    private static final class VersionStore implements IEvalCaseVersionStore {
        EvalCaseVersion value;
        @Override public void insert(EvalCaseVersion value) { this.value = value; }
        @Override public Optional<EvalCaseVersion> find(String id, String version) { return Optional.ofNullable(value); }
        @Override public List<EvalCaseVersion> list(String id) { return value == null ? List.of() : List.of(value); }
        @Override public boolean retire(String id, String version, Instant at) { return false; }
    }
    private static final class HealthStore implements ITraceToEvalStore {
        final List<EvalCaseHealthRecord> values = new ArrayList<>();
        @Override public Optional<EvalCaseCandidate> findCandidate(String id) { return Optional.empty(); }
        @Override public Optional<EvalCaseCandidate> findCandidateBySourceRunAndFailureFamily(String run, String family) { return Optional.empty(); }
        @Override public void insertCandidate(EvalCaseCandidate candidate) { }
        @Override public void updateCandidateStatus(String id, EvalCandidateStatus status) { }
        @Override public void insertReview(EvalCaseReview review) { }
        @Override public void insertLineage(EvalCaseLineage lineage) { }
        @Override public void upsertCaseHealth(EvalCaseHealthRecord health) { values.removeIf(value -> value.getCaseId().equals(health.getCaseId()) && value.getCaseVersion().equals(health.getCaseVersion())); values.add(health); }
        @Override public List<EvalCaseHealthRecord> listCaseHealth(String status, int limit) { return values.stream().filter(value -> status == null || status.equals(value.getHealthStatus())).limit(limit).toList(); }
    }
}
