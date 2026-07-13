package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

@Data
public class EvalEpisodePO {
    private String id; private String evalRunId; private String caseId; private String caseVersion;
    private Integer repetition; private Integer attempt; private String status; private String traceRef;
    private String artifactRefsJson; private Long latencyMs; private Long inputTokens; private Long outputTokens;
    private Double estimatedCost; private String errorClass; private String errorMessage;
}
