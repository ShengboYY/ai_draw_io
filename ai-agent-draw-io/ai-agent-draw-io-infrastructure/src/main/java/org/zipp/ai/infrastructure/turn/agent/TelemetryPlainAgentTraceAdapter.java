package org.zipp.ai.infrastructure.turn.agent;

import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTracePort;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Projects redacted Plain agent facts into the existing run/step trace hierarchy. */
public final class TelemetryPlainAgentTraceAdapter implements PlainAgentTracePort {

    private final AgentUsageTelemetryService telemetry;

    public TelemetryPlainAgentTraceAdapter(AgentUsageTelemetryService telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public void record(PlainAgentTraceEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("attemptId", event.attempt().attemptId());
        metadata.put("attemptEpoch", event.attempt().attemptEpoch());
        metadata.put("step", event.stepNumber());
        put(metadata, "actionType", event.actionType());
        put(metadata, "toolName", event.toolName());
        put(metadata, "argumentsDigest", event.argumentsDigest());
        put(metadata, "beforeDraftDigest", event.beforeDraftDigest());
        put(metadata, "afterDraftDigest", event.afterDraftDigest());
        put(metadata, "outcomeCode", event.outcomeCode());
        metadata.put("issueCount", event.issueCount());
        metadata.put("latencyMs", event.latencyMs());
        telemetry.recordTraceEvent(
                event.type().name().toLowerCase(Locale.ROOT),
                phase(event),
                status(event),
                metadata);
        recordToolSpan(event);
    }

    private void recordToolSpan(PlainAgentTraceEvent event) {
        boolean completedOperation = event.type() == PlainAgentTraceType.TOOL_COMPLETED
                || event.type() == PlainAgentTraceType.COMMIT_COMPLETED;
        if (!completedOperation || event.toolName().isBlank()) {
            return;
        }
        boolean success = event.type() == PlainAgentTraceType.TOOL_COMPLETED
                ? "SUCCESS".equals(event.outcomeCode())
                : "COMMITTED".equals(event.outcomeCode());
        Throwable failure = success
                ? null
                : new IllegalStateException(event.outcomeCode());
        // The completed event owns the measured latency, so persist one real TOOL span per execution.
        AgentUsageTelemetryContext.current().ifPresent(context ->
                telemetry.recordToolCall(
                        context,
                        phase(event),
                        event.toolName(),
                        event.latencyMs(),
                        failure));
    }

    private String status(PlainAgentTraceEvent event) {
        if (event.type() == PlainAgentTraceType.SKILL_LOADING_STARTED
                || event.type() == PlainAgentTraceType.AGENT_STARTED
                || event.type() == PlainAgentTraceType.DECISION_STARTED
                || event.type() == PlainAgentTraceType.TOOL_REQUESTED
                || event.type() == PlainAgentTraceType.VISUAL_REVIEW_STARTED
                || event.type() == PlainAgentTraceType.COMMIT_STARTED) {
            return "RUNNING";
        }
        if (event.type() == PlainAgentTraceType.AGENT_STOPPED
                && !"CANDIDATE_SUBMITTED".equals(event.outcomeCode())) {
            return "FAILED";
        }
        if (event.type() == PlainAgentTraceType.SKILLS_LOADED
                && !"SUCCESS".equals(event.outcomeCode())) {
            return "FAILED";
        }
        if (event.type() == PlainAgentTraceType.TOOL_COMPLETED
                && !"SUCCESS".equals(event.outcomeCode())) {
            return "FAILED";
        }
        if (event.type() == PlainAgentTraceType.COMMIT_COMPLETED
                && !"COMMITTED".equals(event.outcomeCode())) {
            return "FAILED";
        }
        return "SUCCESS";
    }

    private String phase(PlainAgentTraceEvent event) {
        return switch (event.type()) {
            case SKILL_LOADING_STARTED, SKILLS_LOADED -> "plain_agent_skill";
            case DECISION_STARTED, DECISION_SELECTED -> "plain_draw_agent";
            case TOOL_REQUESTED, TOOL_COMPLETED, DRAFT_UPDATED -> "plain_draft";
            case VISUAL_REVIEW_STARTED, VISUAL_REVIEW_COMPLETED ->
                    "plain_visual_review_agent";
            case COMMIT_STARTED, COMMIT_COMPLETED -> "plain_commit";
            default -> "plain_agent";
        };
    }

    private void put(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
