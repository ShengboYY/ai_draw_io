package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

/** A review aid only; it never mutates an Agent or release Gate. */
public record TraceRecommendation(
        String suspectedLayer,
        String evidence,
        String expectedImpact,
        String suggestedExperiment,
        int relatedFindingsCount,
        Double confidence) { }
