package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;

import java.time.Instant;

/** Compact list/detail projection; no trace, XML, or storage path is exposed. */
@Value @Builder
public class EvalRunSummaryView {
    String id; EvalRunMode mode; String datasetId; String datasetVersion; EvaluationTarget evaluationTarget; EvalRunStatus status;
    String gitSha; String executionProfileHash; int repetitions; int totalEpisodes; int completedEpisodes;
    int passCount; int failCount; int errorCount; int unavailableCount; double progress;
    long totalLatencyMs; double estimatedCost; String baselineRef; String candidateRef;
    Instant createdAt; Instant startedAt; Instant completedAt;
}
