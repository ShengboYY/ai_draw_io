package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext;

public interface ICanvasReviewService {

    CanvasReviewContext buildReviewContext(CanvasReviewCommand command);

    String answer(CanvasReviewCommand command);

}
