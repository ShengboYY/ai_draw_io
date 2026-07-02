package org.zipp.ai.test.domain.agent;

import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class FakeAgentUsageTelemetryStore implements IAgentUsageTelemetryStore {

    public final List<AgentRunTelemetry> runs = new ArrayList<>();
    public final List<AgentRunStepTelemetry> steps = new ArrayList<>();
    public final List<LlmCallTelemetry> llmCalls = new ArrayList<>();
    public final List<ToolCallTelemetry> toolCalls = new ArrayList<>();

    @Override
    public void insertRun(AgentRunTelemetry run) {
        runs.add(run);
    }

    @Override
    public void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs) {
        runs.stream()
                .filter(run -> run.getId().equals(runId))
                .findFirst()
                .ifPresent(run -> {
                    run.setStatus(status);
                    run.setErrorClass(errorClass);
                    run.setCompletedAt(completedAt);
                    run.setLatencyMs(latencyMs);
                });
    }

    @Override
    public void insertStep(AgentRunStepTelemetry step) {
        steps.add(step);
    }

    @Override
    public void insertLlmCall(LlmCallTelemetry call) {
        llmCalls.add(call);
    }

    @Override
    public void insertToolCall(ToolCallTelemetry call) {
        toolCalls.add(call);
    }

    @Override
    public AgentUsageSummary summarizeForUser(String userId) {
        long platformRuns = runs.stream()
                .filter(run -> userId.equals(run.getUserId()) && "PLATFORM".equals(run.getCredentialSource()))
                .count();
        long userKeyRuns = runs.stream()
                .filter(run -> userId.equals(run.getUserId()) && "USER_KEY".equals(run.getCredentialSource()))
                .count();
        long unknownTokenCalls = llmCalls.stream()
                .filter(call -> userId.equals(call.getUserId()) && call.getTotalTokens() == null)
                .count();
        long knownTotalTokens = llmCalls.stream()
                .filter(call -> userId.equals(call.getUserId()) && call.getTotalTokens() != null)
                .mapToLong(LlmCallTelemetry::getTotalTokens)
                .sum();
        return AgentUsageSummary.builder()
                .platformRunCount(platformRuns)
                .userKeyRunCount(userKeyRuns)
                .platformLlmCallCount(llmCalls.stream()
                        .filter(call -> userId.equals(call.getUserId()) && "PLATFORM".equals(call.getCredentialSource()))
                        .count())
                .userKeyLlmCallCount(llmCalls.stream()
                        .filter(call -> userId.equals(call.getUserId()) && "USER_KEY".equals(call.getCredentialSource()))
                        .count())
                .toolCallCount(toolCalls.stream().filter(call -> userId.equals(call.getUserId())).count())
                .knownTotalTokens(knownTotalTokens)
                .unknownTokenLlmCallCount(unknownTokenCalls)
                .build();
    }

    public String serializedRecords() {
        return String.valueOf(runs) + steps + llmCalls + toolCalls;
    }
}
