package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class DiagramTraceSnapshotPO {

    private String id;
    private String runId;
    private String spanId;
    private String diagramId;
    private Long version;
    private String canvasHash;
    private String thumbnailUrl;
    private String summary;
    private Integer changedCellCount;
    private Date createdAt;
}
