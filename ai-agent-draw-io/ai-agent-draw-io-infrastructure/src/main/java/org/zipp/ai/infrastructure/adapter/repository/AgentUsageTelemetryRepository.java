package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;
import org.zipp.ai.infrastructure.dao.IAgentUsageTelemetryMapper;
import org.zipp.ai.infrastructure.dao.po.AgentRunStepTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentRunTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentTraceEventPO;
import org.zipp.ai.infrastructure.dao.po.AgentUsageSummaryPO;
import org.zipp.ai.infrastructure.dao.po.AdminUsageSummaryPO;
import org.zipp.ai.infrastructure.dao.po.DiagramTraceSnapshotPO;
import org.zipp.ai.infrastructure.dao.po.LlmCallTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.ToolCallTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.UsageDimensionSummaryPO;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class AgentUsageTelemetryRepository implements IAgentUsageTelemetryStore {

    @Resource
    private IAgentUsageTelemetryMapper agentUsageTelemetryMapper;

    @Value("${zipp.telemetry.delete-batch-size:500}")
    private int deleteBatchSize;

    @Override
    public void insertRun(AgentRunTelemetry run) {
        agentUsageTelemetryMapper.insertRun(toPo(run));
    }

    @Override
    public void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs) {
        agentUsageTelemetryMapper.completeRun(runId, status, errorClass, toDate(completedAt), latencyMs);
    }

    @Override
    public void insertStep(AgentRunStepTelemetry step) {
        agentUsageTelemetryMapper.insertStep(toPo(step));
    }

    @Override
    public void insertLlmCall(LlmCallTelemetry call) {
        agentUsageTelemetryMapper.insertLlmCall(toPo(call));
    }

    @Override
    public void insertToolCall(ToolCallTelemetry call) {
        agentUsageTelemetryMapper.insertToolCall(toPo(call));
    }

    @Override
    public void insertTraceEvent(AgentTraceEvent event) {
        agentUsageTelemetryMapper.insertTraceEvent(toPo(event));
    }

    @Override
    public boolean tryClaimVisualRepair(String sourceRunId,
                                        String reviewedRunId,
                                        String userId,
                                        String diagramId,
                                        String requestId,
                                        Long reviewedVersion,
                                        String reviewedCanvasHash,
                                        int repairRound,
                                        String repairRunId,
                                        Instant occurredAt) {
        if (repairRound < 1 || repairRound > CanvasVisualReviewPolicy.MAX_AUTOMATIC_REPAIR_ROUNDS) {
            return false;
        }
        String metadataJson = JSON.toJSONString(Map.of(
                "repairRunId", repairRunId,
                "repairRound", repairRound,
                "reviewedVersion", reviewedVersion,
                "reviewedCanvasHash", reviewedCanvasHash));
        return agentUsageTelemetryMapper.tryClaimVisualRepair(
                visualRepairClaimId(sourceRunId, repairRound), sourceRunId, reviewedRunId, userId, diagramId,
                requestId, reviewedVersion, reviewedCanvasHash, repairRound, metadataJson, toDate(occurredAt)) == 1;
    }

    @Override
    public boolean isVisualRepairResult(String sourceRunId,
                                        String repairRunId,
                                        String userId,
                                        String diagramId,
                                        Long repairedVersion,
                                        String repairedCanvasHash,
                                        int repairRound) {
        return agentUsageTelemetryMapper.countVisualRepairResult(
                visualRepairClaimId(sourceRunId, repairRound), sourceRunId, repairRunId, userId, diagramId,
                repairedVersion, repairedCanvasHash, repairRound) > 0;
    }

    @Override
    public void insertDiagramSnapshot(AgentDiagramTraceSnapshot snapshot) {
        agentUsageTelemetryMapper.insertDiagramSnapshot(toPo(snapshot));
    }

    @Override
    public int backfillDiagramSnapshotThumbnail(String diagramId, String canvasHash, String thumbnailUrl) {
        return agentUsageTelemetryMapper.backfillDiagramSnapshotThumbnail(diagramId, canvasHash, thumbnailUrl);
    }

    @Override
    public AgentUsageSummary summarizeForUser(String userId) {
        if (StringUtils.isBlank(userId)) {
            return AgentUsageSummary.empty();
        }
        AgentUsageSummaryPO po = agentUsageTelemetryMapper.summarizeByUserId(userId);
        if (po == null) {
            return AgentUsageSummary.empty();
        }
        return AgentUsageSummary.builder()
                .platformRunCount(defaultLong(po.getPlatformRunCount()))
                .userKeyRunCount(defaultLong(po.getUserKeyRunCount()))
                .platformLlmCallCount(defaultLong(po.getPlatformLlmCallCount()))
                .userKeyLlmCallCount(defaultLong(po.getUserKeyLlmCallCount()))
                .toolCallCount(defaultLong(po.getToolCallCount()))
                .knownTotalTokens(defaultLong(po.getKnownTotalTokens()))
                .unknownTokenLlmCallCount(defaultLong(po.getUnknownTokenLlmCallCount()))
                .build();
    }

    @Override
    public AdminUsageSummary summarizeGlobal() {
        AdminUsageSummaryPO po = agentUsageTelemetryMapper.summarizeGlobal();
        if (po == null) {
            return AdminUsageSummary.empty();
        }
        return AdminUsageSummary.builder()
                .requestCount(defaultLong(po.getRequestCount()))
                .successfulRequestCount(defaultLong(po.getSuccessfulRequestCount()))
                .failedRequestCount(defaultLong(po.getFailedRequestCount()))
                .runningRequestCount(defaultLong(po.getRunningRequestCount()))
                .llmCallCount(defaultLong(po.getLlmCallCount()))
                .successfulLlmCallCount(defaultLong(po.getSuccessfulLlmCallCount()))
                .failedLlmCallCount(defaultLong(po.getFailedLlmCallCount()))
                .toolCallCount(defaultLong(po.getToolCallCount()))
                .successfulToolCallCount(defaultLong(po.getSuccessfulToolCallCount()))
                .failedToolCallCount(defaultLong(po.getFailedToolCallCount()))
                .promptTokens(defaultLong(po.getPromptTokens()))
                .completionTokens(defaultLong(po.getCompletionTokens()))
                .totalTokens(defaultLong(po.getTotalTokens()))
                .unknownTokenLlmCallCount(defaultLong(po.getUnknownTokenLlmCallCount()))
                .averageRunLatencyMs(defaultLong(po.getAverageRunLatencyMs()))
                .maxRunLatencyMs(defaultLong(po.getMaxRunLatencyMs()))
                .build();
    }

    @Override
    public List<UsageDimensionSummary> summarizeByProviderModelCredentialSource() {
        return agentUsageTelemetryMapper.summarizeByProviderModelCredentialSource().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentRunTelemetry> listRuns(String status, String userId, String agentId, int limit, int offset) {
        return agentUsageTelemetryMapper.selectRuns(status, userId, agentId, limit, offset).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentRunTelemetry> listTerminalRunsAtOrBefore(Instant snapshot, int limit) {
        if (snapshot == null) {
            return List.of();
        }
        return agentUsageTelemetryMapper.selectTerminalRunsBetween(null, toDate(snapshot), limit).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<AgentRunTelemetry> listTerminalRunsBetween(Instant completedFrom, Instant completedTo, int limit) {
        if (completedTo == null) {
            return List.of();
        }
        return agentUsageTelemetryMapper.selectTerminalRunsBetween(
                        completedFrom == null ? null : toDate(completedFrom), toDate(completedTo), limit).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<AgentRunDetail> findRunDetail(String runId) {
        AgentRunTelemetryPO run = agentUsageTelemetryMapper.selectRunById(runId);
        if (run == null) {
            return Optional.empty();
        }
        return Optional.of(AgentRunDetail.builder()
                .run(toDomain(run))
                .steps(agentUsageTelemetryMapper.selectStepsByRunId(runId).stream()
                        .map(this::toDomain)
                        .collect(Collectors.toList()))
                .llmCalls(agentUsageTelemetryMapper.selectLlmCallsByRunId(runId).stream()
                        .map(this::toDomain)
                        .collect(Collectors.toList()))
                .toolCalls(agentUsageTelemetryMapper.selectToolCallsByRunId(runId).stream()
                        .map(this::toDomain)
                        .collect(Collectors.toList()))
                .traceEvents(agentUsageTelemetryMapper.selectTraceEventsByRunId(runId).stream()
                        .map(this::toDomain)
                        .collect(Collectors.toList()))
                .build());
    }

    @Override
    public List<AgentDiagramTraceSnapshot> listDiagramSnapshots(String runId) {
        return agentUsageTelemetryMapper.selectDiagramSnapshotsByRunId(runId).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public int anonymizeUser(String userId, String anonymizedUserId) {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(anonymizedUserId)) {
            return 0;
        }
        return agentUsageTelemetryMapper.anonymizeRuns(userId, anonymizedUserId)
                + agentUsageTelemetryMapper.anonymizeSteps(userId, anonymizedUserId)
                + agentUsageTelemetryMapper.anonymizeLlmCalls(userId, anonymizedUserId)
                + agentUsageTelemetryMapper.anonymizeToolCalls(userId, anonymizedUserId)
                + agentUsageTelemetryMapper.anonymizeTraceEvents(userId, anonymizedUserId);
    }

    @Override
    public int deleteTelemetryBefore(Instant cutoff) {
        if (cutoff == null) {
            return 0;
        }
        Date cutoffDate = toDate(cutoff);
        int batchSize = Math.max(1, deleteBatchSize);
        int deleted = 0;
        while (true) {
            List<String> runIds = agentUsageTelemetryMapper.selectExpiredRunIds(cutoffDate, batchSize);
            if (runIds == null || runIds.isEmpty()) {
                return deleted;
            }
            int batchDeleted = agentUsageTelemetryMapper.deleteTraceEventsByRunIds(runIds)
                    + agentUsageTelemetryMapper.deleteDiagramSnapshotsByRunIds(runIds)
                    + agentUsageTelemetryMapper.deleteToolCallsByRunIds(runIds)
                    + agentUsageTelemetryMapper.deleteLlmCallsByRunIds(runIds)
                    + agentUsageTelemetryMapper.deleteStepsByRunIds(runIds)
                    + agentUsageTelemetryMapper.deleteRunsByIds(runIds);
            deleted += batchDeleted;
            if (runIds.size() < batchSize || batchDeleted == 0) {
                return deleted;
            }
        }
    }

    private AgentRunTelemetryPO toPo(AgentRunTelemetry run) {
        AgentRunTelemetryPO po = new AgentRunTelemetryPO();
        po.setId(run.getId());
        po.setRequestId(run.getRequestId());
        po.setDiagramId(run.getDiagramId());
        po.setUserId(run.getUserId());
        po.setAgentId(run.getAgentId());
        po.setSessionId(run.getSessionId());
        po.setRequestType(run.getRequestType());
        po.setCredentialSource(run.getCredentialSource());
        po.setModelCredentialId(run.getModelCredentialId());
        po.setStatus(run.getStatus());
        po.setErrorClass(run.getErrorClass());
        po.setStartedAt(toDate(run.getStartedAt()));
        po.setCompletedAt(toDate(run.getCompletedAt()));
        po.setLatencyMs(run.getLatencyMs());
        po.setStepCount(run.getStepCount());
        po.setLlmCallCount(run.getLlmCallCount());
        po.setToolCallCount(run.getToolCallCount());
        po.setTraceEventCount(run.getTraceEventCount());
        po.setKnownTotalTokens(run.getKnownTotalTokens());
        return po;
    }

    private String visualRepairClaimId(String sourceRunId, int repairRound) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceRunId + ":" + repairRound).getBytes(StandardCharsets.UTF_8));
            return "avr_" + HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private DiagramTraceSnapshotPO toPo(AgentDiagramTraceSnapshot snapshot) {
        DiagramTraceSnapshotPO po = new DiagramTraceSnapshotPO();
        po.setId(snapshot.getId());
        po.setRunId(snapshot.getRunId());
        po.setSpanId(snapshot.getSpanId());
        po.setDiagramId(snapshot.getDiagramId());
        po.setVersion(snapshot.getVersion());
        po.setCanvasHash(snapshot.getCanvasHash());
        po.setThumbnailUrl(snapshot.getThumbnailUrl());
        po.setSummary(snapshot.getSummary());
        po.setChangedCellCount(snapshot.getChangedCellCount());
        po.setCreatedAt(toDate(snapshot.getCreatedAt()));
        return po;
    }

    private AgentTraceEventPO toPo(AgentTraceEvent event) {
        AgentTraceEventPO po = new AgentTraceEventPO();
        po.setId(event.getId());
        po.setRunId(event.getRunId());
        po.setParentId(event.getParentId());
        po.setRequestId(event.getRequestId());
        po.setUserId(event.getUserId());
        po.setSequenceNo(event.getSequenceNo());
        po.setEventType(event.getEventType());
        po.setPhase(event.getPhase());
        po.setStatus(event.getStatus());
        po.setMetadataJson(event.getMetadataJson());
        po.setOccurredAt(toDate(event.getOccurredAt()));
        return po;
    }

    private AgentRunStepTelemetryPO toPo(AgentRunStepTelemetry step) {
        AgentRunStepTelemetryPO po = new AgentRunStepTelemetryPO();
        po.setId(step.getId());
        po.setRunId(step.getRunId());
        po.setParentId(step.getParentId());
        po.setUserId(step.getUserId());
        po.setPhase(step.getPhase());
        po.setStatus(step.getStatus());
        po.setErrorClass(step.getErrorClass());
        po.setStartedAt(toDate(step.getStartedAt()));
        po.setCompletedAt(toDate(step.getCompletedAt()));
        po.setLatencyMs(step.getLatencyMs());
        return po;
    }

    private LlmCallTelemetryPO toPo(LlmCallTelemetry call) {
        LlmCallTelemetryPO po = new LlmCallTelemetryPO();
        po.setId(call.getId());
        po.setRunId(call.getRunId());
        po.setParentId(call.getParentId());
        po.setUserId(call.getUserId());
        po.setPhase(call.getPhase());
        po.setProvider(call.getProvider());
        po.setModel(call.getModel());
        po.setCredentialSource(call.getCredentialSource());
        po.setModelCredentialId(call.getModelCredentialId());
        po.setPromptTokens(call.getPromptTokens());
        po.setCompletionTokens(call.getCompletionTokens());
        po.setTotalTokens(call.getTotalTokens());
        po.setPricingVersion(call.getPricingVersion());
        po.setInputPricePerMillionUsd(call.getInputPricePerMillionUsd());
        po.setOutputPricePerMillionUsd(call.getOutputPricePerMillionUsd());
        po.setEstimatedCostUsd(call.getEstimatedCostUsd());
        po.setProviderRequestId(call.getProviderRequestId());
        po.setProviderResponseId(call.getProviderResponseId());
        po.setTtftMs(call.getTtftMs());
        po.setAttemptCount(call.getAttemptCount());
        po.setRetryCount(call.getRetryCount());
        po.setStatus(call.getStatus());
        po.setErrorClass(call.getErrorClass());
        po.setStartedAt(toDate(call.getStartedAt()));
        po.setCompletedAt(toDate(call.getCompletedAt()));
        po.setLatencyMs(call.getLatencyMs());
        return po;
    }

    private ToolCallTelemetryPO toPo(ToolCallTelemetry call) {
        ToolCallTelemetryPO po = new ToolCallTelemetryPO();
        po.setId(call.getId());
        po.setRunId(call.getRunId());
        po.setParentId(call.getParentId());
        po.setUserId(call.getUserId());
        po.setPhase(call.getPhase());
        po.setToolName(call.getToolName());
        po.setStatus(call.getStatus());
        po.setErrorClass(call.getErrorClass());
        po.setStartedAt(toDate(call.getStartedAt()));
        po.setCompletedAt(toDate(call.getCompletedAt()));
        po.setLatencyMs(call.getLatencyMs());
        return po;
    }

    private AgentRunTelemetry toDomain(AgentRunTelemetryPO po) {
        return AgentRunTelemetry.builder()
                .id(po.getId())
                .requestId(po.getRequestId())
                .diagramId(po.getDiagramId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .sessionId(po.getSessionId())
                .requestType(po.getRequestType())
                .credentialSource(po.getCredentialSource())
                .modelCredentialId(po.getModelCredentialId())
                .status(po.getStatus())
                .errorClass(po.getErrorClass())
                .startedAt(toInstant(po.getStartedAt()))
                .completedAt(toInstant(po.getCompletedAt()))
                .latencyMs(po.getLatencyMs())
                .stepCount(defaultLong(po.getStepCount()))
                .llmCallCount(defaultLong(po.getLlmCallCount()))
                .toolCallCount(defaultLong(po.getToolCallCount()))
                .traceEventCount(defaultLong(po.getTraceEventCount()))
                .knownTotalTokens(defaultLong(po.getKnownTotalTokens()))
                .build();
    }

    private AgentTraceEvent toDomain(AgentTraceEventPO po) {
        return AgentTraceEvent.builder()
                .id(po.getId())
                .runId(po.getRunId())
                .parentId(po.getParentId())
                .requestId(po.getRequestId())
                .userId(po.getUserId())
                .sequenceNo(po.getSequenceNo())
                .eventType(po.getEventType())
                .phase(po.getPhase())
                .status(po.getStatus())
                .metadataJson(po.getMetadataJson())
                .occurredAt(toInstant(po.getOccurredAt()))
                .build();
    }

    private AgentRunStepTelemetry toDomain(AgentRunStepTelemetryPO po) {
        return AgentRunStepTelemetry.builder()
                .id(po.getId())
                .runId(po.getRunId())
                .parentId(po.getParentId())
                .userId(po.getUserId())
                .phase(po.getPhase())
                .status(po.getStatus())
                .errorClass(po.getErrorClass())
                .startedAt(toInstant(po.getStartedAt()))
                .completedAt(toInstant(po.getCompletedAt()))
                .latencyMs(po.getLatencyMs())
                .build();
    }

    private LlmCallTelemetry toDomain(LlmCallTelemetryPO po) {
        return LlmCallTelemetry.builder()
                .id(po.getId())
                .runId(po.getRunId())
                .parentId(po.getParentId())
                .userId(po.getUserId())
                .phase(po.getPhase())
                .provider(po.getProvider())
                .model(po.getModel())
                .credentialSource(po.getCredentialSource())
                .modelCredentialId(po.getModelCredentialId())
                .promptTokens(po.getPromptTokens())
                .completionTokens(po.getCompletionTokens())
                .totalTokens(po.getTotalTokens())
                .pricingVersion(po.getPricingVersion())
                .inputPricePerMillionUsd(po.getInputPricePerMillionUsd())
                .outputPricePerMillionUsd(po.getOutputPricePerMillionUsd())
                .estimatedCostUsd(po.getEstimatedCostUsd())
                .providerRequestId(po.getProviderRequestId())
                .providerResponseId(po.getProviderResponseId())
                .ttftMs(po.getTtftMs())
                .attemptCount(po.getAttemptCount())
                .retryCount(po.getRetryCount())
                .status(po.getStatus())
                .errorClass(po.getErrorClass())
                .startedAt(toInstant(po.getStartedAt()))
                .completedAt(toInstant(po.getCompletedAt()))
                .latencyMs(po.getLatencyMs())
                .build();
    }

    private ToolCallTelemetry toDomain(ToolCallTelemetryPO po) {
        return ToolCallTelemetry.builder()
                .id(po.getId())
                .runId(po.getRunId())
                .parentId(po.getParentId())
                .userId(po.getUserId())
                .phase(po.getPhase())
                .toolName(po.getToolName())
                .status(po.getStatus())
                .errorClass(po.getErrorClass())
                .startedAt(toInstant(po.getStartedAt()))
                .completedAt(toInstant(po.getCompletedAt()))
                .latencyMs(po.getLatencyMs())
                .build();
    }

    private AgentDiagramTraceSnapshot toDomain(DiagramTraceSnapshotPO po) {
        return AgentDiagramTraceSnapshot.builder()
                .id(po.getId())
                .runId(po.getRunId())
                .spanId(po.getSpanId())
                .diagramId(po.getDiagramId())
                .version(po.getVersion())
                .canvasHash(po.getCanvasHash())
                .thumbnailUrl(po.getThumbnailUrl())
                .summary(po.getSummary())
                .changedCellCount(po.getChangedCellCount())
                .createdAt(toInstant(po.getCreatedAt()))
                .build();
    }

    private UsageDimensionSummary toDomain(UsageDimensionSummaryPO po) {
        return UsageDimensionSummary.builder()
                .provider(po.getProvider())
                .model(po.getModel())
                .credentialSource(po.getCredentialSource())
                .llmCallCount(defaultLong(po.getLlmCallCount()))
                .successfulCallCount(defaultLong(po.getSuccessfulCallCount()))
                .failedCallCount(defaultLong(po.getFailedCallCount()))
                .promptTokens(defaultLong(po.getPromptTokens()))
                .completionTokens(defaultLong(po.getCompletionTokens()))
                .totalTokens(defaultLong(po.getTotalTokens()))
                .unknownTokenCallCount(defaultLong(po.getUnknownTokenCallCount()))
                .averageLatencyMs(defaultLong(po.getAverageLatencyMs()))
                .build();
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
