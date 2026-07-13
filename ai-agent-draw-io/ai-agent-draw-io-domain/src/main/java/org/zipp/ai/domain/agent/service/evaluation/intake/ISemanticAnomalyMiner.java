package org.zipp.ai.domain.agent.service.evaluation.intake;

import java.util.List;

/** Model boundary for semantic anomaly discovery; input must already be sanitized and de-identified. */
public interface ISemanticAnomalyMiner {
    Finding analyze(String sanitizedTraceProjection);
    String version();

    record Finding(boolean isPotentialAnomaly, double confidence, String failureFamily,
                   List<String> evidence, String suggestedRisk, boolean requiresHumanReview) { }
}
