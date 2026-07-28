package org.zipp.ai.infrastructure.turn.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTracePort;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;

import java.util.Objects;
import java.util.regex.Pattern;

/** Emits compact operational logs while forwarding the same redacted event to telemetry. */
public final class LoggingPlainAgentTraceAdapter implements PlainAgentTracePort {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingPlainAgentTraceAdapter.class);
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final String TEMPLATE = "[plain-agent] event={} attemptId={} epoch={} step={} "
            + "action={} tool={} argumentsDigest={} beforeDigest={} afterDigest={} "
            + "outcome={} issueCount={} latencyMs={}";

    private final PlainAgentTracePort delegate;

    public LoggingPlainAgentTraceAdapter(PlainAgentTracePort delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void record(PlainAgentTraceEvent event) {
        Objects.requireNonNull(event, "event");
        Object[] fields = {
                event.type().name().toLowerCase(java.util.Locale.ROOT),
                safeToken(event.attempt().attemptId()),
                event.attempt().attemptEpoch(),
                event.stepNumber(),
                safeToken(event.actionType()),
                safeToken(event.toolName()),
                safeToken(event.argumentsDigest()),
                safeToken(event.beforeDraftDigest()),
                safeToken(event.afterDraftDigest()),
                safeToken(event.outcomeCode()),
                event.issueCount(),
                event.latencyMs()
        };
        if (failed(event)) {
            LOG.warn(TEMPLATE, fields);
        } else {
            LOG.info(TEMPLATE, fields);
        }
        delegate.record(event);
    }

    private boolean failed(PlainAgentTraceEvent event) {
        return (event.type() == PlainAgentTraceType.AGENT_STOPPED
                && !"CANDIDATE_SUBMITTED".equals(event.outcomeCode()))
                || (event.type() == PlainAgentTraceType.TOOL_COMPLETED
                && !"SUCCESS".equals(event.outcomeCode()));
    }

    private String safeToken(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return SAFE_TOKEN.matcher(value).matches() ? value : "redacted";
    }
}
