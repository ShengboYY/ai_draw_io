package org.zipp.ai.test.domain.agent;

import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FakeAgentUsageTelemetryStore implements IAgentUsageTelemetryStore {

    public final List<AgentRunTelemetry> runs = new ArrayList<>();
    public final List<AgentRunStepTelemetry> steps = new ArrayList<>();
    public final List<LlmCallTelemetry> llmCalls = new ArrayList<>();
    public final List<ToolCallTelemetry> toolCalls = new ArrayList<>();
    public final List<AgentTraceEvent> traceEvents = new ArrayList<>();
    public final List<AgentDiagramTraceSnapshot> diagramSnapshots = new ArrayList<>();
    public Instant deletedBeforeCutoff;
    public int deletedBeforeCount;

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
    public void insertTraceEvent(AgentTraceEvent event) {
        traceEvents.add(event);
    }

    @Override
    public void insertDiagramSnapshot(AgentDiagramTraceSnapshot snapshot) {
        diagramSnapshots.add(snapshot);
    }

    @Override
    public int backfillDiagramSnapshotThumbnail(String diagramId, String canvasHash, String thumbnailUrl) {
        int updated = 0;
        for (AgentDiagramTraceSnapshot snapshot : diagramSnapshots) {
            if (diagramId.equals(snapshot.getDiagramId())
                    && canvasHash.equals(snapshot.getCanvasHash())
                    && snapshot.getThumbnailUrl() == null) {
                snapshot.setThumbnailUrl(thumbnailUrl);
                updated++;
            }
        }
        return updated;
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

    @Override
    public AdminUsageSummary summarizeGlobal() {
        long successfulRuns = runs.stream().filter(run -> "SUCCESS".equals(run.getStatus())).count();
        long failedRuns = runs.stream().filter(run -> "FAILED".equals(run.getStatus())).count();
        long runningRuns = runs.stream().filter(run -> "RUNNING".equals(run.getStatus())).count();
        long successfulLlm = llmCalls.stream().filter(call -> "SUCCESS".equals(call.getStatus())).count();
        long failedLlm = llmCalls.stream().filter(call -> "FAILED".equals(call.getStatus())).count();
        long successfulTools = toolCalls.stream().filter(call -> "SUCCESS".equals(call.getStatus())).count();
        long failedTools = toolCalls.stream().filter(call -> "FAILED".equals(call.getStatus())).count();
        long maxLatency = runs.stream()
                .filter(run -> run.getLatencyMs() != null)
                .mapToLong(AgentRunTelemetry::getLatencyMs)
                .max()
                .orElse(0L);
        long averageLatency = Math.round(runs.stream()
                .filter(run -> run.getLatencyMs() != null)
                .mapToLong(AgentRunTelemetry::getLatencyMs)
                .average()
                .orElse(0D));
        return AdminUsageSummary.builder()
                .requestCount((long) runs.size())
                .successfulRequestCount(successfulRuns)
                .failedRequestCount(failedRuns)
                .runningRequestCount(runningRuns)
                .llmCallCount((long) llmCalls.size())
                .successfulLlmCallCount(successfulLlm)
                .failedLlmCallCount(failedLlm)
                .toolCallCount((long) toolCalls.size())
                .successfulToolCallCount(successfulTools)
                .failedToolCallCount(failedTools)
                .promptTokens(llmCalls.stream().filter(c -> c.getPromptTokens() != null).mapToLong(LlmCallTelemetry::getPromptTokens).sum())
                .completionTokens(llmCalls.stream().filter(c -> c.getCompletionTokens() != null).mapToLong(LlmCallTelemetry::getCompletionTokens).sum())
                .totalTokens(llmCalls.stream().filter(c -> c.getTotalTokens() != null).mapToLong(LlmCallTelemetry::getTotalTokens).sum())
                .unknownTokenLlmCallCount(llmCalls.stream().filter(c -> c.getTotalTokens() == null).count())
                .averageRunLatencyMs(averageLatency)
                .maxRunLatencyMs(maxLatency)
                .build();
    }

    @Override
    public List<UsageDimensionSummary> summarizeByProviderModelCredentialSource() {
        Map<String, List<LlmCallTelemetry>> grouped = llmCalls.stream().collect(Collectors.groupingBy(call ->
                call.getProvider() + "|" + call.getModel() + "|" + call.getCredentialSource()));
        return grouped.values().stream()
                .map(calls -> {
                    LlmCallTelemetry first = calls.get(0);
                    long averageLatency = Math.round(calls.stream()
                            .filter(call -> call.getLatencyMs() != null)
                            .mapToLong(LlmCallTelemetry::getLatencyMs)
                            .average()
                            .orElse(0D));
                    return UsageDimensionSummary.builder()
                            .provider(first.getProvider())
                            .model(first.getModel())
                            .credentialSource(first.getCredentialSource())
                            .llmCallCount((long) calls.size())
                            .successfulCallCount(calls.stream().filter(call -> "SUCCESS".equals(call.getStatus())).count())
                            .failedCallCount(calls.stream().filter(call -> "FAILED".equals(call.getStatus())).count())
                            .promptTokens(calls.stream().filter(c -> c.getPromptTokens() != null).mapToLong(LlmCallTelemetry::getPromptTokens).sum())
                            .completionTokens(calls.stream().filter(c -> c.getCompletionTokens() != null).mapToLong(LlmCallTelemetry::getCompletionTokens).sum())
                            .totalTokens(calls.stream().filter(c -> c.getTotalTokens() != null).mapToLong(LlmCallTelemetry::getTotalTokens).sum())
                            .unknownTokenCallCount(calls.stream().filter(c -> c.getTotalTokens() == null).count())
                            .averageLatencyMs(averageLatency)
                            .build();
                })
                .sorted(Comparator.comparing(UsageDimensionSummary::getProvider))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<AgentRunDetail> findRunDetail(String runId) {
        return runs.stream()
                .filter(run -> run.getId().equals(runId))
                .findFirst()
                .map(run -> AgentRunDetail.builder()
                        .run(run)
                        .steps(steps.stream().filter(step -> runId.equals(step.getRunId())).collect(Collectors.toList()))
                        .llmCalls(llmCalls.stream().filter(call -> runId.equals(call.getRunId())).collect(Collectors.toList()))
                        .toolCalls(toolCalls.stream().filter(call -> runId.equals(call.getRunId())).collect(Collectors.toList()))
                        .traceEvents(traceEvents.stream().filter(event -> runId.equals(event.getRunId())).collect(Collectors.toList()))
                        .build());
    }

    @Override
    public List<AgentDiagramTraceSnapshot> listDiagramSnapshots(String runId) {
        return diagramSnapshots.stream()
                .filter(snapshot -> runId.equals(snapshot.getRunId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentRunTelemetry> listRuns(String status, String userId, String agentId, int limit, int offset) {
        return runs.stream()
                .filter(run -> status == null || status.equals(run.getStatus()))
                .filter(run -> userId == null || userId.equals(run.getUserId()))
                .filter(run -> agentId == null || agentId.equals(run.getAgentId()))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public int deleteTelemetryBefore(Instant cutoff) {
        deletedBeforeCutoff = cutoff;
        return deletedBeforeCount;
    }

    public String serializedRecords() {
        return String.valueOf(runs) + steps + llmCalls + toolCalls + traceEvents + diagramSnapshots;
    }
}
