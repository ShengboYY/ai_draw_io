package org.zipp.ai.domain.agent.model.valobj.visualreview;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CanvasVisualReviewEvidence {

    private CanvasVisualReviewEvidenceRole role;
    private String pageId;
    private String pageName;
    private Integer tileIndex;
    private Integer tileCount;
    private Integer width;
    private Integer height;
    private String dataUrl;
}
