package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.PlainDrawPlan;

/** Renders and reviews a working draft without mutating it or the committed canvas. */
@FunctionalInterface
public interface DiagramDraftVisualReviewPort {

    DiagramDraftVisualReview review(
            PlainDrawPlan plan,
            DiagramDraftSnapshot draft);

    DiagramDraftVisualReviewPort UNAVAILABLE = (plan, draft) ->
            DiagramDraftVisualReview.unavailable(draft.digest(), "reviewer_unavailable");
}
