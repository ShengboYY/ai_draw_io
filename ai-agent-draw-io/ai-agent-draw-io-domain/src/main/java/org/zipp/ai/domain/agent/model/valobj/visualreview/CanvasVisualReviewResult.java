package org.zipp.ai.domain.agent.model.valobj.visualreview;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class CanvasVisualReviewResult {

    private boolean available;
    private String summary;
    private List<CanvasVisualIssue> issues;
    private boolean recommendedHumanReview;
    private String unavailableReason;
    private String reviewerVersion;

    public static CanvasVisualReviewResult unavailable(String reason) {
        return CanvasVisualReviewResult.builder()
                .available(false)
                .summary("")
                .issues(Collections.emptyList())
                .unavailableReason(reason)
                .build();
    }

    public List<CanvasVisualIssue> safeIssues() {
        return issues == null ? Collections.emptyList() : issues;
    }
}
