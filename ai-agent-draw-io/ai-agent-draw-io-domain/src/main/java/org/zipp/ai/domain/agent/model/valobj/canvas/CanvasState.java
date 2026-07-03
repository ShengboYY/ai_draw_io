package org.zipp.ai.domain.agent.model.valobj.canvas;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class CanvasState {

    private String userId;
    private String diagramId;
    private String title;
    private String diagramType;
    private String thumbnailUrl;
    private String currentXml;
    private String summary;
    private String analysisJson;
    private Long version;
    private Date createdAt;
    private Date updatedAt;

}
