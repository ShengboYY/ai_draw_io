package org.zipp.ai.domain.agent.model.valobj.canvas;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CanvasState {

    private String userId;
    private String diagramId;
    private String diagramType;
    private String currentXml;
    private String summary;
    private String analysisJson;
    private Long version;

}
