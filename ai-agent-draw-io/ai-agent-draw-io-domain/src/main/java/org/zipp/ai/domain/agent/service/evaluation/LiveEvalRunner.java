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
        int repeat = Math.max(1, repetitions);
        List<EvalSampleResult> samples = new ArrayList<>();
        for (EvalCaseDefinition evalCase : cases) {
            for (int repetition = 0; repetition < repeat; repetition++) {
                samples.add(runEpisode(evalCase, repetition, factory, judge));
            }
        }
        return samples;
    }

    private EvalSampleResult runEpisode(EvalCaseDefinition evalCase, int repetition,
                                        LiveExecutionFactory factory, IEvalJudge judge) {
        long started = System.nanoTime();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                EvalExecution execution = factory.execute(evalCase);
                EvalHarnessResult deterministic = harness.evaluate(execution);
                EvalHarnessResult.Status status = deterministic.getStatus();
                boolean passed = deterministic.isPassed();
                if (Boolean.TRUE.equals(evalCase.getExpected().getJudgeRequired())) {
                    if (judge == null || !judge.isCalibrated()) return sample(evalCase, repetition, EvalHarnessResult.Status.UNAVAILABLE,
                            false, execution, started, "JudgeUnavailable");
                    EvalJudgeResult judged = judge.judge(judgeInput(evalCase, execution, deterministic));
                    if (judged == null || !judged.isAvailable()) return sample(evalCase, repetition,
                            EvalHarnessResult.Status.UNAVAILABLE, false, execution, started, "JudgeUnavailable");
                    if (!judged.isPassed() || judged.getCriticalIssues() > 0) {
                        status = EvalHarnessResult.Status.FAIL; passed = false;
                    }
                }
                return sample(evalCase, repetition, status, passed, execution, started, null);
            } catch (EvalInfrastructureException e) {
                if (attempt == 2) return sample(evalCase, repetition, EvalHarnessResult.Status.ERROR,
                        false, null, started, e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                return sample(evalCase, repetition, EvalHarnessResult.Status.ERROR,
                        false, null, started, e.getClass().getSimpleName());
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

    private EvalSampleResult sample(EvalCaseDefinition evalCase, int repetition, EvalHarnessResult.Status status,
                                    boolean passed, EvalExecution execution, long started, String errorClass) {
        return EvalSampleResult.builder().caseId(evalCase.getCaseId()).slice(evalCase.getRisk()).repetition(repetition)
                .status(status).passed(passed).latencyMs((System.nanoTime() - started) / 1_000_000)
                .inputTokens(execution == null ? 0 : execution.getInputTokens())
                .outputTokens(execution == null ? 0 : execution.getOutputTokens())
                .estimatedCost(execution == null ? 0 : execution.getEstimatedCost()).errorClass(errorClass).build();
    }

    @FunctionalInterface
    public interface LiveExecutionFactory { EvalExecution execute(EvalCaseDefinition evalCase); }
}
