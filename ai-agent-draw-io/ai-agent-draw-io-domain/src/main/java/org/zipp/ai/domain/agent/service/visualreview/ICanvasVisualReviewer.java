package org.zipp.ai.domain.agent.service.visualreview;

import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;

public interface ICanvasVisualReviewer {

    CanvasVisualReviewResult review(CanvasVisualReviewCommand command);
}
