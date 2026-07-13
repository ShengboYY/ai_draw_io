package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCanaryAssessment;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalGateDecisionRecord;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalGateOutcome;
import org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Connects an eligible Release Gate to aggregate canary evidence without deploying or rolling back anything. */
@Service
public class EvalCanaryOperationsService {
    private final IEvalRunStore runs;
    private final IEvalCanaryAssessmentStore assessments;
    private final Clock clock;

    @Autowired
    public EvalCanaryOperationsService(IEvalRunStore runs, IEvalCanaryAssessmentStore assessments) {
        this(runs, assessments, Clock.systemUTC());
    }
    public EvalCanaryOperationsService(IEvalRunStore runs, IEvalCanaryAssessmentStore assessments, Clock clock) {
        this.runs = runs; this.assessments = assessments; this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvalCanaryAssessment assess(String evalRunId, String deploymentRef, String policyVersion,
            EvalCanaryService.Window baseline, EvalCanaryService.Window canary, EvalCanaryService.Policy policy,
            String actor) {
        if (StringUtils.isAnyBlank(evalRunId, deploymentRef, policyVersion, actor) || canary == null || policy == null) {
            throw new IllegalArgumentException("run, deployment, policy, canary metrics and actor are required");
        }
        runs.findRun(evalRunId).orElseThrow(() -> new IllegalArgumentException("Eval Run not found"));
        EvalGateDecisionRecord gate = runs.findGate(evalRunId)
                .orElseThrow(() -> new IllegalStateException("Release Gate decision is unavailable"));
        boolean eligible = gate.getOutcome() == EvalGateOutcome.PASS
                || (gate.getOutcome() == EvalGateOutcome.BLOCK && gate.isOverrideApproved());
        if (!eligible) throw new IllegalStateException("Release Gate is not eligible for canary observation");
        validate(policy);
        validateWindow(baseline, "baseline");
        validateWindow(canary, "canary");
        EvalCanaryService.Decision decision = new EvalCanaryService().evaluate(baseline, canary, policy);
        EvalCanaryAssessment value = EvalCanaryAssessment.builder().id("eca_" + UUID.randomUUID())
                .evalRunId(evalRunId).deploymentRef(StringUtils.left(deploymentRef, 180)).policyVersion(StringUtils.left(policyVersion, 120))
                .outcome(decision.outcome()).reasons(decision.reasons())
                .baselineRequests(baseline == null ? 0 : baseline.requests()).canaryRequests(canary.requests())
                .canaryFailures(canary.failures()).criticalFindings(canary.criticalFindings())
                .infrastructureErrors(canary.infrastructureErrors()).p95LatencyMs(canary.p95LatencyMs())
                .averageCost(canary.averageCost()).createdBy(actor).createdAt(clock.instant()).build();
        assessments.insert(value); return value;
    }

    public List<EvalCanaryAssessment> list(String evalRunId, int limit) {
        if (StringUtils.isBlank(evalRunId)) throw new IllegalArgumentException("evalRunId is required");
        return assessments.list(evalRunId, Math.max(1, Math.min(limit, 100)));
    }

    private void validate(EvalCanaryService.Policy value) {
        if (value.minimumRequests() < 1 || value.maximumFailureRateIncrease() < 0D
                || value.maximumLatencyRatio() < 1D || value.maximumCostRatio() < 1D
                || value.maximumInfrastructureErrorRate() < 0D || value.maximumInfrastructureErrorRate() > 1D) {
            throw new IllegalArgumentException("canary policy thresholds are invalid");
        }
    }

    private void validateWindow(EvalCanaryService.Window value, String name) {
        if (value == null) return;
        if (value.requests() < 0 || value.failures() < 0 || value.failures() > value.requests()
                || value.criticalFindings() < 0 || value.infrastructureErrors() < 0
                || value.infrastructureErrors() > value.requests() || value.p95LatencyMs() < 0D
                || value.averageCost() < 0D) {
            throw new IllegalArgumentException(name + " canary window is invalid");
        }
    }
}
