package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

import java.util.List;

/** Target-aware read model built only from persisted Episodes and their evidence. */
@Value
@Builder
public class EvalTargetReport {
    String runId;
    EvaluationTarget target;
    int totalEpisodes;
    int eligibleEpisodes;
    int excludedErrors;
    int excludedUnavailable;
    LatencySummary latency;
    FullAgentMetrics fullAgent;
    RouterMetrics router;
    DrawingMetrics drawing;

    public enum Availability { AVAILABLE, COUNT_ONLY, UNAVAILABLE }

    @Value @Builder
    public static class LatencySummary {
        int sampleCount;
        Long medianMs;
        Long maxMs;
        Long p95Ms;
        Availability p95Availability;
        String p95Reason;
    }

    @Value @Builder
    public static class FullAgentMetrics {
        Availability availability;
        String unavailableReason;
        Double tsrAtOne;
        Double ciLower;
        Double ciUpper;
        Double errorRate;
        double estimatedCost;
        List<FunnelStage> funnel;
    }

    @Value @Builder
    public static class FunnelStage {
        String stage;
        int inputCount;
        int eligibleCount;
        int passedCount;
        int unavailableCount;
        Double passRate;
        Availability availability;
        List<String> failedEpisodeIds;
        List<String> unavailableEpisodeIds;
    }

    @Value @Builder
    public static class RouterMetrics {
        Double accuracy;
        int classifiedCount;
        int invalidCount;
        int evidenceUnavailableCount;
        Double macroF1;
        Availability macroF1Availability;
        String macroF1Reason;
        Double repeatStability;
        Availability stabilityAvailability;
        String stabilityReason;
        List<ConfusionCell> confusionMatrix;
        List<RouteMetric> perRoute;
    }

    @Value @Builder
    public static class ConfusionCell {
        String expectedRoute;
        String actualRoute;
        int count;
        List<String> episodeIds;
    }

    @Value @Builder
    public static class RouteMetric {
        String route;
        int support;
        Double precision;
        Double recall;
        Double f1;
    }

    @Value @Builder
    public static class DrawingMetrics {
        List<GraderLayer> layers;
        List<SeverityCount> issueSeverities;
        List<DrawingEvidence> evidence;
        int judgeAvailableCount;
        int judgeUnavailableCount;
        int judgeNotRequiredCount;
    }

    @Value @Builder
    public static class GraderLayer {
        String graderName;
        int eligibleCount;
        int passedCount;
        int failedCount;
        int unavailableCount;
        int notRequiredCount;
        Double passRate;
        Availability availability;
        List<String> failedEpisodeIds;
        List<String> unavailableEpisodeIds;
    }

    @Value @Builder
    public static class SeverityCount {
        String severity;
        int count;
        List<String> episodeIds;
    }

    @Value @Builder
    public static class DrawingEvidence {
        String episodeId;
        String caseId;
        String artifactRef;
        boolean beforeAvailable;
        boolean afterAvailable;
        Boolean canvasChanged;
    }
}
