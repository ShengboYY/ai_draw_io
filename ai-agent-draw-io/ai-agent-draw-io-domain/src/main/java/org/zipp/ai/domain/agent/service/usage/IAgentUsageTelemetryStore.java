package org.zipp.ai.domain.agent.service.usage;

import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IAgentUsageTelemetryStore {

    void insertRun(AgentRunTelemetry run);

    void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs);

    void insertStep(AgentRunStepTelemetry step);

    void insertLlmCall(LlmCallTelemetry call);

    void insertToolCall(ToolCallTelemetry call);

    default void insertTraceEvent(AgentTraceEvent event) {
    }

    AgentUsageSummary summarizeForUser(String userId);

    AdminUsageSummary summarizeGlobal();

    List<UsageDimensionSummary> summarizeByProviderModelCredentialSource();

    Optional<AgentRunDetail> findRunDetail(String runId);

    default int anonymizeUser(String userId, String anonymizedUserId) {
        return 0;
    }

    default int deleteTelemetryBefore(Instant cutoff) {
        return 0;
    }
}
