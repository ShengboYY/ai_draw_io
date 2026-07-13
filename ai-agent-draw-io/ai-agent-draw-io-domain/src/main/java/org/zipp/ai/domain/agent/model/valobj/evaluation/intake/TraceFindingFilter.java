package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import java.time.Instant;

/** Query contract for the restricted Trace Findings workspace. */
public record TraceFindingFilter(
        EvalCandidateStatus status,
        String risk,
        String analyzerType,
        String routeType,
        String agentId,
        String sourceRunId,
        Instant discoveredFrom,
        Instant discoveredTo,
        Long minLatencyMs,
        Long maxLatencyMs,
        int limit,
        int offset) { }
