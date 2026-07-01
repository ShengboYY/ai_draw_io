package org.zipp.ai.api.dto;

import lombok.Data;

import java.util.Date;

@Data
public class DiagramSummaryResponseDTO {

    private String diagramId;
    private String title;
    private String diagramType;
    private Long version;
    private Date updatedAt;

}
