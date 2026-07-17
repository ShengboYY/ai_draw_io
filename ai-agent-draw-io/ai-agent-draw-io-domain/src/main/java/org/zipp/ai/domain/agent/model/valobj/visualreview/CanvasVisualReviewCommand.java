package org.zipp.ai.domain.agent.model.valobj.visualreview;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;

import java.util.List;

@Data
@Builder
public class CanvasVisualReviewCommand {

    private CanvasVisualReviewStage stage;
    private String originalUserTask;
    private String diagramType;
    private String beforeImageDataUrl;
    private String afterImageDataUrl;
    private String afterImagePageId;
    private String afterImagePageName;
    private Integer totalPageCount;
    private Integer truncatedPageCount;
    private List<CanvasVisualReviewEvidence> additionalAfterImages;
    private List<String> analyzerEvidence;
    private String canvasSummary;
    private List<CanvasCellData> canvasCells;
    private String languageHint;
    private String rendererVersion;
    private Long expectedVersion;
    private String expectedContentHash;
}
