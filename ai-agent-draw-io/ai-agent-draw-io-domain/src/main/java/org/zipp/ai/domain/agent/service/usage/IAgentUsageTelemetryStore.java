package org.zipp.ai.domain.agent.service.usage;

import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;

import java.time.Instant;

public interface IAgentUsageTelemetryStore {

    void insertRun(AgentRunTelemetry run);

    void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs);

    void insertStep(AgentRunStepTelemetry step);

    void insertLlmCall(LlmCallTelemetry call);

    void insertToolCall(ToolCallTelemetry call);

    AgentUsageSummary summarizeForUser(String userId);
}
