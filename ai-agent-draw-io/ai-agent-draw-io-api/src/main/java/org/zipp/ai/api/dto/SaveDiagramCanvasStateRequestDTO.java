package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class SaveDiagramCanvasStateRequestDTO {

    private String userId;
    private Long expectedVersion;
    private String expectedContentHash;
    private String canvasXml;

}
