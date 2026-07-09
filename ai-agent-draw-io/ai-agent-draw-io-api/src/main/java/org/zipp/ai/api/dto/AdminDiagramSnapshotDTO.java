package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminDiagramSnapshotDTO {

    private String id;
    private String runId;
    private String spanId;
    private String diagramId;
    private Long version;
    private String canvasHash;
    private String thumbnailUrl;
    private String summary;
    private Instant createdAt;
}
