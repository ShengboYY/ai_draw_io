package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.PlainDrawPlan;

/** Renders and reviews a working draft without mutating it or the committed canvas. */
@FunctionalInterface
public interface DiagramDraftVisualReviewPort {

    DiagramDraftVisualReview review(
            PlainDrawPlan plan,
            DiagramDraftSnapshot draft);

    /**
     * True when the authoritative review image must come from the client-side Draw.io renderer.
     * The runtime then commits the candidate without invoking the server-side draft renderer.
     */
    default boolean defersToClientRenderedEvidence() {
        return false;
    }

    DiagramDraftVisualReviewPort UNAVAILABLE = (plan, draft) ->
            DiagramDraftVisualReview.unavailable(draft.digest(), "reviewer_unavailable");
}
