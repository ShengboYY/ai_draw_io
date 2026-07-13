package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

/** Candidate write model paired with read-only metadata for one Finding row. */
public record TraceFindingCandidate(EvalCaseCandidate candidate, TraceFindingContext context) { }
