package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalTargetGateCompositionService;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalRunStore;

import java.util.*;

import static org.junit.Assert.*;

public class EvalTargetGateCompositionServiceTest {

    @Test
    public void passesWhenEveryRequiredTargetPasses() {
        MemoryRuns runs = new MemoryRuns();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS, false);
        runs.add("drawing", EvaluationTarget.DRAWING_QUALITY, EvalGateOutcome.PASS, false);

        EvalTargetGateCompositionService.Decision result = service(runs).compose(
                Map.of(EvaluationTarget.INTENT_ROUTER, "router", EvaluationTarget.DRAWING_QUALITY, "drawing"),
                Set.of(EvaluationTarget.INTENT_ROUTER, EvaluationTarget.DRAWING_QUALITY));

        assertEquals(EvalGateOutcome.PASS, result.outcome());
        assertEquals(0, result.exitCode());
    }

    @Test
    public void blocksWhenARequiredTargetBlocks() {
        MemoryRuns runs = new MemoryRuns();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.BLOCK, false);

        EvalTargetGateCompositionService.Decision result = service(runs).compose(
                Map.of(EvaluationTarget.INTENT_ROUTER, "router"), Set.of(EvaluationTarget.INTENT_ROUTER));

        assertEquals(EvalGateOutcome.BLOCK, result.outcome());
        assertEquals(1, result.exitCode());
    }

    @Test
    public void returnsNoDecisionWhenARequiredTargetIsMissing() {
        EvalTargetGateCompositionService.Decision result = service(new MemoryRuns()).compose(
                Map.of(), Set.of(EvaluationTarget.FULL_AGENT));

        assertEquals(EvalGateOutcome.NO_DECISION, result.outcome());
        assertEquals(2, result.exitCode());
    }

    @Test
    public void optionalStatisticalBlockIsOnlyAWarning() {
        MemoryRuns runs = new MemoryRuns();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS, false);
        runs.add("drawing", EvaluationTarget.DRAWING_QUALITY, EvalGateOutcome.BLOCK, false);

        EvalTargetGateCompositionService.Decision result = service(runs).compose(
                Map.of(EvaluationTarget.INTENT_ROUTER, "router", EvaluationTarget.DRAWING_QUALITY, "drawing"),
                Set.of(EvaluationTarget.INTENT_ROUTER));

        assertEquals(EvalGateOutcome.PASS, result.outcome());
        assertTrue(result.warnings().get(0).contains("optional Gate is BLOCK"));
    }

    @Test
    public void optionalDeterministicHardFailureStillBlocks() {
        MemoryRuns runs = new MemoryRuns();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS, EvalEpisodeStatus.PASS, false);
        runs.add("drawing", EvaluationTarget.DRAWING_QUALITY, EvalGateOutcome.BLOCK, EvalEpisodeStatus.FAIL, true);

        EvalTargetGateCompositionService.Decision result = service(runs).compose(
                Map.of(EvaluationTarget.INTENT_ROUTER, "router", EvaluationTarget.DRAWING_QUALITY, "drawing"),
                Set.of(EvaluationTarget.INTENT_ROUTER));

        assertEquals(EvalGateOutcome.BLOCK, result.outcome());
        assertTrue(result.reasons().get(0).contains("hard failure"));
    }

    @Test
    public void hardFailureBlocksEvenBeforeThePerRunGateExists() {
        MemoryRuns runs = new MemoryRuns();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS, EvalEpisodeStatus.FAIL, true);
        runs.gates.remove("router");

        EvalTargetGateCompositionService.Decision result = service(runs).compose(
                Map.of(EvaluationTarget.INTENT_ROUTER, "router"), Set.of(EvaluationTarget.INTENT_ROUTER));

        assertEquals(EvalGateOutcome.BLOCK, result.outcome());
    }

    @Test
    public void optionalJudgeFailureWithoutDeterministicGraderFailureIsOnlyAWarning() {
        MemoryRuns runs = new MemoryRuns();
        runs.add("router", EvaluationTarget.INTENT_ROUTER, EvalGateOutcome.PASS, EvalEpisodeStatus.PASS, false);
        runs.add("drawing", EvaluationTarget.DRAWING_QUALITY, EvalGateOutcome.BLOCK, EvalEpisodeStatus.FAIL, false);

        EvalTargetGateCompositionService.Decision result = service(runs).compose(
                Map.of(EvaluationTarget.INTENT_ROUTER, "router", EvaluationTarget.DRAWING_QUALITY, "drawing"),
                Set.of(EvaluationTarget.INTENT_ROUTER));

        assertEquals(EvalGateOutcome.PASS, result.outcome());
        assertTrue(result.warnings().get(0).contains("optional Gate is BLOCK"));
    }

    private EvalTargetGateCompositionService service(MemoryRuns runs) {
        return new EvalTargetGateCompositionService(runs);
    }

    private static class MemoryRuns implements IEvalRunStore {
        private final Map<String, EvalRun> runs = new HashMap<>();
        private final Map<String, EvalGateDecisionRecord> gates = new HashMap<>();
        private final Map<String, List<EvalEpisode>> episodes = new HashMap<>();
        private final Map<String, List<EvalGraderResultRecord>> graders = new HashMap<>();

        void add(String id, EvaluationTarget target, EvalGateOutcome outcome, boolean hardFailure) {
            add(id, target, outcome, hardFailure ? EvalEpisodeStatus.FAIL : EvalEpisodeStatus.PASS, hardFailure);
        }

        void add(String id, EvaluationTarget target, EvalGateOutcome outcome,
                 EvalEpisodeStatus episodeStatus, boolean deterministicFailure) {
            runs.put(id, EvalRun.builder().id(id).mode(EvalRunMode.RELEASE).evaluationTarget(target).build());
            gates.put(id, EvalGateDecisionRecord.builder().evalRunId(id).outcome(outcome)
                    .reasonsJson("[\"fixture decision\"]").build());
            String episodeId = "episode-" + id;
            episodes.put(id, List.of(EvalEpisode.builder().id(episodeId).evalRunId(id).status(episodeStatus).build()));
            graders.put(episodeId, deterministicFailure
                    ? List.of(EvalGraderResultRecord.builder().episodeId(episodeId)
                        .graderName("deterministic").status(EvalEpisodeStatus.FAIL).severity("critical").build())
                    : List.of(EvalGraderResultRecord.builder().episodeId(episodeId)
                        .graderName("deterministic").status(EvalEpisodeStatus.PASS).severity("none").build()));
        }

        public void insertRun(EvalRun run) { runs.put(run.getId(), run); }
        public void updateRun(EvalRun run) { runs.put(run.getId(), run); }
        public Optional<EvalRun> findRun(String runId) { return Optional.ofNullable(runs.get(runId)); }
        public Optional<EvalRun> findByIdempotencyKey(String key) { return Optional.empty(); }
        public List<EvalRun> listRuns(int limit, int offset) { return List.copyOf(runs.values()); }
        public void saveEpisode(EvalEpisode episode) { episodes.computeIfAbsent(episode.getEvalRunId(), ignored -> new ArrayList<>()).add(episode); }
        public Optional<EvalEpisode> findEpisode(String episodeId) { return episodes.values().stream().flatMap(List::stream).filter(value -> episodeId.equals(value.getId())).findFirst(); }
        public List<EvalEpisode> listEpisodes(String runId) { return episodes.getOrDefault(runId, List.of()); }
        public void replaceGraders(String episodeId, List<EvalGraderResultRecord> graders) { }
        public List<EvalGraderResultRecord> listGraders(String episodeId) { return graders.getOrDefault(episodeId, List.of()); }
        public void saveGate(EvalGateDecisionRecord gate) { gates.put(gate.getEvalRunId(), gate); }
        public Optional<EvalGateDecisionRecord> findGate(String runId) { return Optional.ofNullable(gates.get(runId)); }
    }
}
