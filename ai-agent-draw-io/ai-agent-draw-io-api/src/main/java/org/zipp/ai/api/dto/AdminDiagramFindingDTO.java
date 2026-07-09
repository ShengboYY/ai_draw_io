package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class AdminDiagramFindingDTO {

    private String severity;
    private String code;
    private String title;
    private String description;
    private String spanId;
    private String diagramId;
    private String suggestion;
}
