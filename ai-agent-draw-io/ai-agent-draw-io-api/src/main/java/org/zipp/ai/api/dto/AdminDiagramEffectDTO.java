package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class AdminDiagramEffectDTO {

    private String diagramId;
    private Long beforeVersion;
    private Long afterVersion;
    private String beforeHash;
    private String afterHash;
    private Boolean xmlChanged;
    private Boolean thumbnailChanged;
    private String renderStatus;
    private String thumbnailUrl;
}
