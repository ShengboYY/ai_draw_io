package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class EvalStatisticalReport {
    private Decision decision;
    private int totalSamples;
    private int eligibleSamples;
    private int eligibleCases;
    private double tsrAtOne;
    private double ciLower;
    private double ciUpper;
    private double errorRate;
    private double graderAvailability;
    private long totalLatencyMs;
    private long inputTokens;
    private long outputTokens;
    private double estimatedCost;
    private Map<String, Double> perCaseSuccessProbability;

    public enum Decision { READY, NO_DECISION }

    @Data
    @Builder
    public static class Comparison {
        private Decision decision;
        private boolean blocked;
        private double delta;
        private double ciLower;
        private double ciUpper;
        private int pairedCases;
    }
}
