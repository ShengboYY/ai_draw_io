package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class MaterialCatalogVersionPO {
    private String versionId;
    private int versionNo;
    private String detectedMime;
    private long byteSize;
    private Integer pageCount;
    private String ingestState;
    private String processingState;
    private String processingStage;
    private int progress;
    private Instant createdAt;
}
