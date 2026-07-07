package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.Date;

@Data
public class DiagramCanvasStateResponseDTO {

    private String diagramId;
    private String userId;
    private String title;
    private String diagramType;
    private String thumbnailUrl;
    private String currentXml;
    private String contentHash;
    private String saveStatus;
    private String summary;
    private Long version;
    private Date updatedAt;

}
