package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class MaterialCatalogItemPO {
    private String materialId;
    private String kind;
    private String displayName;
    private String retentionClass;
    private String lifecycleState;
    private String latestVersionId;
    private Integer latestVersionNo;
    private String ingestState;
    private String processingState;
    private String processingStage;
    private int progress;
    private Integer pageCount;
    private Instant updatedAt;
}
