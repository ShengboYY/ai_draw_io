package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.*;

import java.util.ArrayList;
import java.util.List;

/** Repeated @1 live runner. FAIL is never retried; only explicit infrastructure ERROR is retried. */
public class LiveEvalRunner {
    private final DefaultEvalHarness harness;

    public LiveEvalRunner() { this(new DefaultEvalHarness()); }
    public LiveEvalRunner(DefaultEvalHarness harness) { this.harness = harness; }

    public List<EvalSampleResult> run(List<EvalCaseDefinition> cases, int repetitions,
                                      LiveExecutionFactory factory, IEvalJudge judge) {
        return runDetailed(cases, repetitions, factory, judge).stream().map(EpisodeResult::sample).toList();
    }

    /** Returns the evidence needed by the Control Plane without changing statistics semantics. */
    public List<EpisodeResult> runDetailed(List<EvalCaseDefinition> cases, int repetitions,
                                           LiveExecutionFactory factory, IEvalJudge judge) {
        int repeat = Math.max(1, repetitions);
        List<EpisodeResult> samples = new ArrayList<>();
        for (EvalCaseDefinition evalCase : cases) {
            for (int repetition = 0; repetition < repeat; repetition++) {
                samples.add(runEpisode(evalCase, repetition, factory, judge));
            }
        }
        return samples;
    }

    private EpisodeResult runEpisode(EvalCaseDefinition evalCase, int repetition,
                                     LiveExecutionFactory factory, IEvalJudge judge) {
        long started = System.nanoTime();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                EvalExecution execution = factory.execute(evalCase);
                EvalHarnessResult deterministic = harness.evaluate(execution);
                EvalHarnessResult.Status status = deterministic.getStatus();
                boolean passed = deterministic.isPassed();
                EvalJudgeResult judged = null;
                if (Boolean.TRUE.equals(evalCase.getExpected().getJudgeRequired())) {
                    if (judge == null || !judge.isCalibrated()) return result(evalCase, repetition, EvalHarnessResult.Status.UNAVAILABLE,
                            false, execution, deterministic, null, started, "JudgeUnavailable");
                    judged = judge.judge(judgeInput(evalCase, execution, deterministic));
                    if (judged == null || !judged.isAvailable()) return result(evalCase, repetition,
                            EvalHarnessResult.Status.UNAVAILABLE, false, execution, deterministic, judged, started, "JudgeUnavailable");
                    if (!judged.isPassed() || judged.getCriticalIssues() > 0) {
                        status = EvalHarnessResult.Status.FAIL; passed = false;
                    }
                }
                return result(evalCase, repetition, status, passed, execution, deterministic, judged, started, null);
            } catch (EvalInfrastructureException e) {
                if (attempt == 2) return result(evalCase, repetition, EvalHarnessResult.Status.ERROR,
                        false, null, null, null, started, e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                return result(evalCase, repetition, EvalHarnessResult.Status.ERROR,
                        false, null, null, null, started, e.getClass().getSimpleName());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private IEvalJudge.JudgeInput judgeInput(EvalCaseDefinition evalCase, EvalExecution execution,
                                             EvalHarnessResult deterministic) {
        Object user = evalCase.getInput().getOrDefault("user", evalCase.getInput().get("turns"));
        java.util.Map<String, String> aliases = evalCase.getExpected().getGraph() == null
                ? java.util.Map.of() : evalCase.getExpected().getGraph().getAliases();
        DrawioGraphNormalizer normalizer = new DrawioGraphNormalizer();
        DrawioGraphNormalizer.Graph initialGraph = normalizer.normalize(execution.getInitialCanvasXml(), aliases);
        DrawioGraphNormalizer.Graph finalGraph = normalizer.normalize(execution.getFinalCanvasXml(), aliases);
        List<String> issues = deterministic.getGraders().stream().flatMap(result -> result.getEvidence().stream()).toList();
        List<String> tools = execution.getTrace().getToolCalls().stream()
                .map(call -> call.getName() + ":" + call.getStatus()).toList();
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        String rubric = evalCase.getDiagramType() == null || "none".equalsIgnoreCase(evalCase.getDiagramType())
                ? "answer-rubric-v1" : "diagram-rubric-v1:" + evalCase.getDiagramType();
        IEvalJudge.EvaluatedAgentVersion version = new IEvalJudge.EvaluatedAgentVersion(
                profile == null ? null : profile.getModel(), profile == null ? null : profile.getTemperature(),
                "judge-input-v2", rubric);
        return new IEvalJudge.JudgeInput(evalCase.getCaseId(), evalCase.getDiagramType(), String.valueOf(user),
                initialGraph, finalGraph, execution.getResponseText(), issues, tools, version);
    }

    private EpisodeResult result(EvalCaseDefinition evalCase, int repetition, EvalHarnessResult.Status status,
                                 boolean passed, EvalExecution execution, EvalHarnessResult deterministic,
                                 EvalJudgeResult judge, long started, String errorClass) {
        EvalSampleResult sample = EvalSampleResult.builder().caseId(evalCase.getCaseId()).slice(evalCase.getRisk()).repetition(repetition)
                .status(status).passed(passed).latencyMs((System.nanoTime() - started) / 1_000_000)
                .inputTokens(execution == null ? 0 : execution.getInputTokens())
                .outputTokens(execution == null ? 0 : execution.getOutputTokens())
                .estimatedCost(execution == null ? 0 : execution.getEstimatedCost()).errorClass(errorClass).build();
        return new EpisodeResult(sample, execution, deterministic, judge);
    }

    public record EpisodeResult(EvalSampleResult sample, EvalExecution execution,
                                EvalHarnessResult deterministic, EvalJudgeResult judge) { }

    @FunctionalInterface
    public interface LiveExecutionFactory { EvalExecution execute(EvalCaseDefinition evalCase); }
}
