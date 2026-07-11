package org.zipp.ai.domain.agent.service.evaluation;

import java.util.ArrayList;
import java.util.List;

/** Compares a canary window to an approved production baseline without performing deployment actions. */
public class EvalCanaryService {
    public Decision evaluate(Window baseline, Window canary, Policy policy) {
        List<String> reasons = new ArrayList<>();
        if (canary == null || canary.requests() < policy.minimumRequests()) {
            return new Decision(Outcome.NO_DECISION, List.of("canary window has insufficient requests"));
        }
        if (canary.criticalFindings() > 0) {
            return new Decision(Outcome.HALT_RECOMMENDED, List.of("critical findings observed in canary"));
        }
        if (baseline != null && baseline.requests() > 0) {
            double baselineFailure = baseline.failures() / (double) baseline.requests();
            double canaryFailure = canary.failures() / (double) canary.requests();
            if (canaryFailure - baselineFailure > policy.maximumFailureRateIncrease()) {
                reasons.add("failure rate increase exceeded threshold");
            }
            if (baseline.p95LatencyMs() > 0 && canary.p95LatencyMs() / baseline.p95LatencyMs() > policy.maximumLatencyRatio()) {
                reasons.add("p95 latency ratio exceeded threshold");
            }
            if (baseline.averageCost() > 0 && canary.averageCost() / baseline.averageCost() > policy.maximumCostRatio()) {
                reasons.add("average cost ratio exceeded threshold");
            }
        }
        double infrastructureRate = canary.infrastructureErrors() / (double) canary.requests();
        if (infrastructureRate > policy.maximumInfrastructureErrorRate()) {
            return new Decision(Outcome.NO_DECISION, List.of("canary infrastructure error rate is too high"));
        }
        return reasons.isEmpty() ? new Decision(Outcome.CONTINUE, List.of("canary metrics remain within approved bounds"))
                : new Decision(Outcome.HALT_RECOMMENDED, reasons);
    }

    public enum Outcome { CONTINUE, HALT_RECOMMENDED, NO_DECISION }
    public record Window(int requests, int failures, int criticalFindings, int infrastructureErrors,
                         double p95LatencyMs, double averageCost) { }
    public record Policy(int minimumRequests, double maximumFailureRateIncrease, double maximumLatencyRatio,
                         double maximumCostRatio, double maximumInfrastructureErrorRate) { }
    public record Decision(Outcome outcome, List<String> reasons) { }
}
