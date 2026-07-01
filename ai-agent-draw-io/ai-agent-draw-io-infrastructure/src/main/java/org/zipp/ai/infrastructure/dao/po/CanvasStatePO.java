package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for the current Draw.io canvas state. */
@Data
public class CanvasStatePO {

    private String diagramId;
    private String userId;
    private String diagramType;
    private String currentXml;
    private String summary;
    private String analysisJson;
    private Long version;
    private Date createdAt;
    private Date updatedAt;

}
