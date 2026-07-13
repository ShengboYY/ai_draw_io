package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRunMode;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvaluationProfileVersion;

import java.util.List;

/** Source-controlled first-party Profile versions; changing behavior requires a new version. */
public final class DefaultEvaluationProfiles {
    private DefaultEvaluationProfiles() { }

    public static List<EvaluationProfileVersion> versions() {
        return List.of(
                profile("full-agent-smoke", EvaluationTarget.FULL_AGENT, "full_agent", EvalRunMode.MODE_B, 1, false,
                        "stubbed", 0D, "route-tool-v1,xml-integrity-v1,visual-quality-v1", "tsr_at_1,median_latency_ms,max_latency_ms"),
                profile("full-agent-release", EvaluationTarget.FULL_AGENT, "full_agent", EvalRunMode.MODE_C, 5, true,
                        "gpt-5.5", 0.2D, "route-tool-v1,xml-integrity-v1,graph-assertion-v1,semantic-preservation-v1,response-judge-v1", "tsr_at_1,ci,error_rate,cost,median_latency_ms,max_latency_ms"),
                profile("router-deterministic", EvaluationTarget.INTENT_ROUTER, "intent_router", EvalRunMode.MODE_B, 1, false,
                        "stubbed", 0D, "route-exact-match-v2", "accuracy,macro_f1,confusion_matrix,invalid_route_rate,median_latency_ms,max_latency_ms"),
                profile("router-live", EvaluationTarget.INTENT_ROUTER, "intent_router", EvalRunMode.MODE_C, 5, true,
                        "gpt-5.5", 0D, "route-exact-match-v2", "accuracy,macro_f1,confusion_matrix,invalid_route_rate,median_latency_ms,max_latency_ms"),
                profile("drawing-structure", EvaluationTarget.DRAWING_QUALITY, "drawing", EvalRunMode.MODE_B, 1, false,
                        "stubbed", 0D, "xml-integrity-v1,graph-assertion-v1,semantic-preservation-v1,visual-quality-v1", "structure_pass_rate,preservation_rate,median_latency_ms,max_latency_ms"),
                profile("drawing-visual", EvaluationTarget.DRAWING_QUALITY, "drawing", EvalRunMode.MODE_C, 3, true,
                        "gpt-5.5", 0D, "xml-integrity-v1,graph-assertion-v1,visual-quality-v1,visual-judge-v1", "visual_pass_rate,judge_availability,cost,median_latency_ms,max_latency_ms")
        );
    }

    private static EvaluationProfileVersion profile(String id, EvaluationTarget target, String adapter,
                                                     EvalRunMode mode, int repetitions, boolean gateEligible,
                                                     String model, double temperature, String graders, String metrics) {
        int minimumCases = mode == EvalRunMode.MODE_B ? 1 : 20;
        int minimumPairedCases = gateEligible ? 20 : 1;
        String judge = graders.contains("response-judge")
                ? ",\"judgePolicy\":{\"kind\":\"text\",\"modelVersion\":\"gpt-5.5\",\"temperature\":0,\"promptVersion\":\"eval-answer-judge-prompt-v2\",\"rubricVersion\":\"eval-answer-judge-rubric-v1\",\"schemaVersion\":\"eval-answer-judge-schema-v2\",\"calibrationRequired\":true}"
                : graders.contains("visual-judge")
                ? ",\"judgePolicy\":{\"kind\":\"visual\",\"modelVersion\":\"gpt-5.5\",\"temperature\":0,\"promptVersion\":\"visual-judge-prompt-v1\",\"rubricVersion\":\"visual-judge-rubric-v1\",\"schemaVersion\":\"visual-judge-schema-v1\",\"calibrationRequired\":true}"
                : "";
        String config = """
                {"model":"%s","modelVersion":"%s","temperature":%s,"credentialAlias":"server-default","credentialVersion":"1","promptConfigHash":"prompt-v1","skillCatalogHash":"skills-v1","toolPolicyVersion":"tool-policy-v1","maxReviewIterations":2,"inputPricePerMillion":0,"outputPricePerMillion":0,"timeoutMs":60000,"maxEstimatedCost":10,"graders":"%s","metrics":"%s","gatePolicy":{"minimumCases":%d,"maximumErrorRate":0.05,"minimumPairedCases":%d,"regressionThreshold":0.01,"minSamplesPerClass":20,"minLatencySamplesForP95":100}%s}
                """.formatted(model, model, temperature, graders, metrics, minimumCases, minimumPairedCases, judge).trim();
        return new EvaluationProfileVersion(id, "1", target, adapter, mode, repetitions, gateEligible, config);
    }
}
