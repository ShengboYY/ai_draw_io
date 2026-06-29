package org.zipp.ai.domain.agent.model.valobj.review;

import lombok.Data;

@Data
public class SemanticContentReview {

    private String overallRisk;

    private String summary;

    private String issues;

    private String recommendations;

    public static SemanticContentReview unavailable(String reason) {
        SemanticContentReview review = new SemanticContentReview();
        review.setOverallRisk("unknown");
        review.setSummary(reason);
        review.setIssues("");
        review.setRecommendations("");
        return review;
    }

}
