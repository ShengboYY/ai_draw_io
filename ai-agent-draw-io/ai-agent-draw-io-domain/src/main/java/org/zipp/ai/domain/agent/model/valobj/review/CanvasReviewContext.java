package org.zipp.ai.domain.agent.model.valobj.review;

import org.zipp.ai.domain.agent.model.valobj.quality.DiagramQualityReport;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CanvasReviewContext {

    private DiagramQualityReport qualityReport;

    private SemanticContentReview semanticReview;

    private String serializedContext;

}
