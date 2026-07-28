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
                "plain_agent",
                status(event),
                metadata);
        recordToolSpan(event);
    }

    private void recordToolSpan(PlainAgentTraceEvent event) {
        if (event.type() != PlainAgentTraceType.TOOL_COMPLETED || event.toolName().isBlank()) {
            return;
        }
        Throwable failure = "SUCCESS".equals(event.outcomeCode())
                ? null
                : new IllegalStateException(event.outcomeCode());
        // The completed event owns the measured latency, so persist one real TOOL span per execution.
        AgentUsageTelemetryContext.current().ifPresent(context ->
                telemetry.recordToolCall(
                        context,
                        "plain_agent",
                        event.toolName(),
                        event.latencyMs(),
                        failure));
    }

    private String status(PlainAgentTraceEvent event) {
        if (event.type() == PlainAgentTraceType.AGENT_STARTED
                || event.type() == PlainAgentTraceType.TOOL_REQUESTED
                || event.type() == PlainAgentTraceType.DECISION_SELECTED) {
            return "RUNNING";
        }
        if (event.type() == PlainAgentTraceType.AGENT_STOPPED
                && !"CANDIDATE_SUBMITTED".equals(event.outcomeCode())) {
            return "FAILED";
        }
        return "SUCCESS";
    }

    private void put(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
