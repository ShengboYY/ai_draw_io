package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalSampleResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalStatisticalReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/** Case-clustered TSR@1 estimation and deterministic bootstrap intervals. */
public class EvalStatisticsService {
    private static final int BOOTSTRAP_REPLICATES = 2_000;

    public EvalStatisticalReport summarize(List<EvalSampleResult> samples, int minimumCases, double maximumErrorRate) {
        List<EvalSampleResult> values = samples == null ? List.of() : samples;
        Map<String, Double> perCase = probabilities(values);
        int errorCount = (int) values.stream().filter(sample -> sample.getStatus() == EvalHarnessResult.Status.ERROR).count();
        int availableCount = (int) values.stream().filter(sample -> sample.getStatus() != EvalHarnessResult.Status.UNAVAILABLE).count();
        int eligible = (int) values.stream().filter(this::eligible).count();
        double errorRate = values.isEmpty() ? 1D : errorCount / (double) values.size();
        double tsr = mean(new ArrayList<>(perCase.values()));
        double[] ci = bootstrap(new ArrayList<>(perCase.values()), 7L);
        boolean ready = perCase.size() >= minimumCases && errorRate <= maximumErrorRate;
        return EvalStatisticalReport.builder().decision(ready ? EvalStatisticalReport.Decision.READY : EvalStatisticalReport.Decision.NO_DECISION)
                .totalSamples(values.size()).eligibleSamples(eligible).eligibleCases(perCase.size())
                .tsrAtOne(tsr).ciLower(ci[0]).ciUpper(ci[1]).errorRate(errorRate)
                .graderAvailability(values.isEmpty() ? 0D : availableCount / (double) values.size())
                .totalLatencyMs(values.stream().mapToLong(EvalSampleResult::getLatencyMs).sum())
                .inputTokens(values.stream().mapToLong(EvalSampleResult::getInputTokens).sum())
                .outputTokens(values.stream().mapToLong(EvalSampleResult::getOutputTokens).sum())
                .estimatedCost(values.stream().mapToDouble(EvalSampleResult::getEstimatedCost).sum())
                .perCaseSuccessProbability(perCase).build();
    }

    public EvalStatisticalReport.Comparison compare(List<EvalSampleResult> baseline, List<EvalSampleResult> candidate,
                                                     int minimumPairedCases, double regressionThreshold) {
        Map<String, Double> base = probabilities(baseline);
        Map<String, Double> next = probabilities(candidate);
        List<Double> differences = base.keySet().stream().filter(next::containsKey)
                .sorted().map(caseId -> next.get(caseId) - base.get(caseId)).toList();
        double[] ci = bootstrap(differences, 11L);
        boolean ready = differences.size() >= minimumPairedCases;
        return EvalStatisticalReport.Comparison.builder()
                .decision(ready ? EvalStatisticalReport.Decision.READY : EvalStatisticalReport.Decision.NO_DECISION)
                .blocked(ready && ci[1] < -regressionThreshold).delta(mean(differences))
                .ciLower(ci[0]).ciUpper(ci[1]).pairedCases(differences.size()).build();
    }

    private Map<String, Double> probabilities(List<EvalSampleResult> samples) {
        if (samples == null) return Map.of();
        Map<String, List<EvalSampleResult>> grouped = samples.stream().filter(this::eligible)
                .collect(Collectors.groupingBy(EvalSampleResult::getCaseId, LinkedHashMap::new, Collectors.toList()));
        Map<String, Double> result = new LinkedHashMap<>();
        grouped.forEach((caseId, rows) -> result.put(caseId,
                rows.stream().filter(EvalSampleResult::isPassed).count() / (double) rows.size()));
        return result;
    }

    private boolean eligible(EvalSampleResult sample) {
        return sample != null && (sample.getStatus() == EvalHarnessResult.Status.PASS
                || sample.getStatus() == EvalHarnessResult.Status.FAIL);
    }

    private double[] bootstrap(List<Double> values, long seed) {
        if (values == null || values.isEmpty()) return new double[]{0D, 0D};
        Random random = new Random(seed);
        List<Double> estimates = new ArrayList<>(BOOTSTRAP_REPLICATES);
        for (int replicate = 0; replicate < BOOTSTRAP_REPLICATES; replicate++) {
            double sum = 0D;
            for (int index = 0; index < values.size(); index++) sum += values.get(random.nextInt(values.size()));
            estimates.add(sum / values.size());
        }
        estimates.sort(Double::compareTo);
        return new double[]{percentile(estimates, 0.025), percentile(estimates, 0.975)};
    }

    private double percentile(List<Double> sorted, double probability) {
        int index = (int) Math.floor(probability * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double mean(List<Double> values) {
        return values == null || values.isEmpty() ? 0D : values.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
    }
}
