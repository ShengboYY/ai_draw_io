package org.zipp.ai.infrastructure.adapter.repository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;
import org.zipp.ai.infrastructure.dao.IAgentUsageTelemetryMapper;
import org.zipp.ai.infrastructure.dao.po.AgentRunStepTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentRunTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentUsageSummaryPO;
import org.zipp.ai.infrastructure.dao.po.LlmCallTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.ToolCallTelemetryPO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;

@Repository
public class AgentUsageTelemetryRepository implements IAgentUsageTelemetryStore {

    @Resource
    private IAgentUsageTelemetryMapper agentUsageTelemetryMapper;

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

    private AgentRunTelemetryPO toPo(AgentRunTelemetry run) {
        AgentRunTelemetryPO po = new AgentRunTelemetryPO();
        po.setId(run.getId());
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
        return po;
    }

    private AgentRunStepTelemetryPO toPo(AgentRunStepTelemetry step) {
        AgentRunStepTelemetryPO po = new AgentRunStepTelemetryPO();
        po.setId(step.getId());
        po.setRunId(step.getRunId());
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
        po.setUserId(call.getUserId());
        po.setPhase(call.getPhase());
        po.setProvider(call.getProvider());
        po.setModel(call.getModel());
        po.setCredentialSource(call.getCredentialSource());
        po.setModelCredentialId(call.getModelCredentialId());
        po.setPromptTokens(call.getPromptTokens());
        po.setCompletionTokens(call.getCompletionTokens());
        po.setTotalTokens(call.getTotalTokens());
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

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }
}
