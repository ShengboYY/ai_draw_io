package org.zipp.ai.domain.agent.service.evaluation;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;

import java.util.List;

/** Compares a versioned Judge against adjudicated human reference labels. */
public class JudgeCalibrationService {
    public Report calibrate(List<Item> items, int minimumItems, double minimumAccuracy, double minimumCriticalRecall) {
        List<Item> values = items == null ? List.of() : items;
        int correct = 0, truePositive = 0, falsePositive = 0, falseNegative = 0, scored = 0;
        String version = null;
        for (Item item : values) {
            EvalJudgeResult result = item.result();
            if (result == null || !result.isAvailable()) continue;
            scored++;
            if (version == null) version = result.getJudgeVersion();
            else if (!java.util.Objects.equals(version, result.getJudgeVersion())) throw new IllegalArgumentException("mixed judge versions");
            if (item.referencePass() == result.isPassed()) correct++;
            boolean predictedCritical = result.getCriticalIssues() > 0;
            if (item.referenceCritical() && predictedCritical) truePositive++;
            else if (!item.referenceCritical() && predictedCritical) falsePositive++;
            else if (item.referenceCritical()) falseNegative++;
        }
        double accuracy = scored == 0 ? 0D : correct / (double) scored;
        double precision = truePositive + falsePositive == 0 ? 1D : truePositive / (double) (truePositive + falsePositive);
        double recall = truePositive + falseNegative == 0 ? 1D : truePositive / (double) (truePositive + falseNegative);
        boolean approved = scored >= minimumItems && accuracy >= minimumAccuracy && recall >= minimumCriticalRecall;
        return Report.builder().judgeVersion(version).itemCount(scored).accuracy(accuracy)
                .criticalPrecision(precision).criticalRecall(recall).approved(approved).build();
    }

    public record Item(boolean referencePass, boolean referenceCritical, EvalJudgeResult result) { }

    @Data
    @Builder
    public static class Report {
        private String judgeVersion;
        private int itemCount;
        private double accuracy;
        private double criticalPrecision;
        private double criticalRecall;
        private boolean approved;
    }
}
