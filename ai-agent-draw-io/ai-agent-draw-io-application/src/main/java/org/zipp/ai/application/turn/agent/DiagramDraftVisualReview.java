package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Visual-review result bound to the exact draft digest that was rendered. */
public record DiagramDraftVisualReview(
        String reviewedDigest,
        String decision,
        boolean available,
        String summary,
        List<DiagramDraftVisualIssue> issues,
        String groundingConflict,
        String reviewerVersion
) {

    public DiagramDraftVisualReview {
        reviewedDigest = safe(reviewedDigest);
        decision = safe(decision);
        summary = safe(summary);
        issues = List.copyOf(issues == null ? List.of() : issues);
        groundingConflict = safe(groundingConflict);
        reviewerVersion = safe(reviewerVersion);
        if (reviewedDigest.isBlank() || decision.isBlank()) {
            throw new IllegalArgumentException("visual review identity is required");
        }
    }

    public static DiagramDraftVisualReview unavailable(String digest, String reason) {
        return new DiagramDraftVisualReview(
                digest,
                "UNAVAILABLE",
                false,
                "",
                List.of(),
                reason,
                "");
    }

    public boolean reviews(DiagramDraftView draft) {
        return draft != null && reviewedDigest.equals(draft.digest());
    }

    public boolean requestsRepair() {
        return "REPAIR".equals(decision);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
