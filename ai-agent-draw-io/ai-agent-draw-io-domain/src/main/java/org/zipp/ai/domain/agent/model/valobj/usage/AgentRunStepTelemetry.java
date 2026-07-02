package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Logical phase metadata; intentionally excludes prompts, responses, and diagram XML. */
@Data
@Builder
public class AgentRunStepTelemetry {

    private String id;
    private String runId;
    private String userId;
    private String phase;
    private String status;
    private String errorClass;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
}
