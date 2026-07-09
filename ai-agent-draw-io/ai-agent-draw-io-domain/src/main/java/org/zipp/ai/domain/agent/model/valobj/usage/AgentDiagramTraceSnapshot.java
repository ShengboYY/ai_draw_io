package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Metadata snapshot of a saved diagram canvas; raw XML is intentionally excluded. */
@Data
@Builder
public class AgentDiagramTraceSnapshot {

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
