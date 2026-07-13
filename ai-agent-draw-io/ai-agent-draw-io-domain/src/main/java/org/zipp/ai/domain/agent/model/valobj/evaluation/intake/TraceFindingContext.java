package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import java.time.Instant;

/** Safe run and review metadata projected in bulk for the Findings list. */
public record TraceFindingContext(
        String routeType,
        String sourceAgentId,
        Long sourceLatencyMs,
        String reviewedBy,
        Instant reviewedAt) { }
