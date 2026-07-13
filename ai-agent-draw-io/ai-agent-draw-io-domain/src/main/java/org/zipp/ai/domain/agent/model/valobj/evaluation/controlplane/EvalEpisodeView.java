package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Case Matrix row with deterministic metadata and grader outcomes only. */
@Value @Builder
public class EvalEpisodeView {
    String id; String caseId; String caseVersion; int repetition; int attempt; EvalEpisodeStatus status;
    String route; String risk; String language; String diagramType; String agent;
    long latencyMs; double estimatedCost; String errorClass; String errorMessage; String blockingReason;
    List<EvalGraderResultRecord> graders;
    EvalJudgeResultRecord judge;
}
