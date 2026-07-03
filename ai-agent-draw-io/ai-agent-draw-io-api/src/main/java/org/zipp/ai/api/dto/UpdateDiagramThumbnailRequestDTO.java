package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class UpdateDiagramThumbnailRequestDTO {

    private String userId;
    private String thumbnailDataUrl;

}
