package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class CanvasVisualReviewEvidenceDTO {

    private String role;
    private String pageId;
    private String pageName;
    private Integer tileIndex;
    private Integer tileCount;
    private String dataUrl;
}
