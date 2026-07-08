package org.zipp.ai.domain.agent.model.valobj.usage;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Complete metadata-only run view for admin troubleshooting. */
@Data
@Builder
public class AgentRunDetail {

    private AgentRunTelemetry run;
    @Builder.Default
    private List<AgentRunStepTelemetry> steps = List.of();
    @Builder.Default
    private List<LlmCallTelemetry> llmCalls = List.of();
    @Builder.Default
    private List<ToolCallTelemetry> toolCalls = List.of();
    @Builder.Default
    private List<AgentTraceEvent> traceEvents = List.of();
}
