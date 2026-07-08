package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.infrastructure.dao.IAgentDebugTraceMapper;
import org.zipp.ai.infrastructure.dao.po.DebugTraceCapturePO;
import org.zipp.ai.infrastructure.dao.po.DebugTraceControlPO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Repository
public class AgentDebugTraceRepository implements IAgentDebugTraceStore {

    @Resource
    private IAgentDebugTraceMapper agentDebugTraceMapper;

    @Override
    public void insertControl(DebugTraceControl control) {
        agentDebugTraceMapper.insertControl(toPo(control));
    }

    @Override
    public List<DebugTraceControl> listEnabledControls() {
        return agentDebugTraceMapper.listEnabledControls().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void insertCapture(DebugTraceCapture capture) {
        agentDebugTraceMapper.insertCapture(toPo(capture));
    }

    @Override
    public List<DebugTraceCapture> listCapturesByRunId(String runId) {
        return agentDebugTraceMapper.listCapturesByRunId(runId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public int deleteExpiredContent(Instant now) {
        return agentDebugTraceMapper.deleteExpiredContent(toDate(now));
    }

    @Override
    public int extendRunContentExpiry(String runId, Instant expiresAt) {
        return agentDebugTraceMapper.extendRunContentExpiry(runId, toDate(expiresAt));
    }

    @Override
    public int deleteContentForUser(String userId, String anonymizedUserId, Instant deletedAt) {
        return agentDebugTraceMapper.deleteContentForUser(userId, anonymizedUserId, toDate(deletedAt))
                + agentDebugTraceMapper.redactControlsForUser(userId, anonymizedUserId, toDate(deletedAt));
    }

    private DebugTraceControlPO toPo(DebugTraceControl control) {
        DebugTraceControlPO po = new DebugTraceControlPO();
        po.setId(control.getId());
        po.setCreatedByUserId(control.getCreatedByUserId());
        po.setScopeUserId(control.getScopeUserId());
        po.setScopeRunId(control.getScopeRunId());
        po.setScopeStartsAt(toDate(control.getScopeStartsAt()));
        po.setScopeEndsAt(toDate(control.getScopeEndsAt()));
        po.setEnabled(control.isEnabled());
        po.setCreatedAt(toDate(control.getCreatedAt()));
        po.setDisabledAt(toDate(control.getDisabledAt()));
        return po;
    }

    private DebugTraceCapturePO toPo(DebugTraceCapture capture) {
        DebugTraceCapturePO po = new DebugTraceCapturePO();
        po.setId(capture.getId());
        po.setControlId(capture.getControlId());
        po.setUserId(capture.getUserId());
        po.setRunId(capture.getRunId());
        po.setEventType(capture.getEventType());
        po.setContent(capture.getContent());
        po.setContentSha256(capture.getContentSha256());
        po.setContentExpiresAt(toDate(capture.getContentExpiresAt()));
        po.setContentDeletedAt(toDate(capture.getContentDeletedAt()));
        po.setCreatedAt(toDate(capture.getCreatedAt()));
        return po;
    }

    private DebugTraceControl toDomain(DebugTraceControlPO po) {
        return DebugTraceControl.builder()
                .id(po.getId())
                .createdByUserId(po.getCreatedByUserId())
                .scopeUserId(po.getScopeUserId())
                .scopeRunId(po.getScopeRunId())
                .scopeStartsAt(toInstant(po.getScopeStartsAt()))
                .scopeEndsAt(toInstant(po.getScopeEndsAt()))
                .enabled(Boolean.TRUE.equals(po.getEnabled()))
                .createdAt(toInstant(po.getCreatedAt()))
                .disabledAt(toInstant(po.getDisabledAt()))
                .build();
    }

    private DebugTraceCapture toDomain(DebugTraceCapturePO po) {
        return DebugTraceCapture.builder()
                .id(po.getId())
                .controlId(po.getControlId())
                .userId(po.getUserId())
                .runId(po.getRunId())
                .eventType(po.getEventType())
                .content(po.getContent())
                .contentSha256(po.getContentSha256())
                .contentExpiresAt(toInstant(po.getContentExpiresAt()))
                .contentDeletedAt(toInstant(po.getContentDeletedAt()))
                .createdAt(toInstant(po.getCreatedAt()))
                .build();
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
