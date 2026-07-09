package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Tool metadata only; arguments and tool responses are deliberately excluded. */
@Data
@Builder
public class ToolCallTelemetry {

    private String id;
    private String runId;
    private String parentId;
    private String userId;
    private String phase;
    private String toolName;
    private String status;
    private String errorClass;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
}
