package org.zipp.ai.domain.agent.service.usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentTelemetryMetrics {

    private static final String UNKNOWN = "unknown";
    private static final int MAX_TAG_VALUE_LENGTH = 64;
    private static final AgentTelemetryMetrics NOOP = new AgentTelemetryMetrics((MeterRegistry) null);

    private final MeterRegistry registry;
    private final AtomicBoolean telemetryWriterGaugesRegistered = new AtomicBoolean(false);

    public AgentTelemetryMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider == null ? null : registryProvider.getIfAvailable());
    }

    public AgentTelemetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static AgentTelemetryMetrics noop() {
        return NOOP;
    }

    public void registerTelemetryWriter(AgentUsageTelemetryService.TelemetryWriteExecutor executor) {
        if (registry == null || executor == null || !telemetryWriterGaugesRegistered.compareAndSet(false, true)) {
            return;
        }
        Gauge.builder("ai.agent.telemetry.queue.size", executor, AgentUsageTelemetryService.TelemetryWriteExecutor::queueSize)
                .description("Current queued telemetry writes.")
                .strongReference(true)
                .register(registry);
        Gauge.builder("ai.agent.telemetry.queue.remaining.capacity", executor, AgentUsageTelemetryService.TelemetryWriteExecutor::queueRemainingCapacity)
                .description("Remaining telemetry writer queue capacity.")
                .strongReference(true)
                .register(registry);
    }

    public void recordRun(String requestType, String credentialSource, String status, long latencyMs) {
        Tags tags = Tags.of(
                "request_type", lowCardinalityTag(requestType),
                "credential_source", lowCardinalityTag(credentialSource),
                "status", lowCardinalityTag(status));
        increment("ai.agent.run", tags, 1D);
        recordTimer("ai.agent.run.latency", tags, latencyMs);
    }

    public void recordLlmCall(String phase,
                              String provider,
                              String model,
                              String credentialSource,
                              String status,
                              Long latencyMs,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens) {
        String modelTag = modelTag(credentialSource, model);
        Tags callTags = Tags.of(
                "phase", lowCardinalityTag(phase),
                "provider", lowCardinalityTag(provider),
                "model", modelTag,
                "credential_source", lowCardinalityTag(credentialSource),
                "status", lowCardinalityTag(status));
        increment("ai.agent.llm.call", callTags, 1D);
        if (latencyMs != null) {
            recordTimer("ai.agent.llm.call.latency", callTags, latencyMs);
        }

        Tags tokenTags = Tags.of(
                "provider", lowCardinalityTag(provider),
                "model", modelTag,
                "credential_source", lowCardinalityTag(credentialSource));
        incrementTokens(tokenTags, "prompt", promptTokens);
        incrementTokens(tokenTags, "completion", completionTokens);
        incrementTokens(tokenTags, "total", totalTokens);
    }

    public void recordToolCall(String phase, String toolName, String status, Long latencyMs) {
        Tags tags = Tags.of(
                "phase", lowCardinalityTag(phase),
                "tool_name", lowCardinalityTag(toolName),
                "status", lowCardinalityTag(status));
        increment("ai.agent.tool.call", tags, 1D);
        if (latencyMs != null) {
            recordTimer("ai.agent.tool.call.latency", tags, latencyMs);
        }
    }

    public void recordTelemetryWriteDropped() {
        increment("ai.agent.telemetry.write.dropped", Tags.empty(), 1D);
    }

    public void recordDebugTraceCapture(String eventType) {
        increment("ai.agent.debug.trace.capture",
                Tags.of("event_type", lowCardinalityTag(eventType)), 1D);
    }

    public void recordDebugTraceView(String outcome) {
        increment("ai.agent.debug.trace.view",
                Tags.of("outcome", lowCardinalityTag(outcome)), 1D);
    }

    private void incrementTokens(Tags baseTags, String tokenType, Integer amount) {
        if (amount == null || amount <= 0) {
            return;
        }
        increment("ai.agent.llm.tokens", baseTags.and("token_type", tokenType), amount.doubleValue());
    }

    private void increment(String name, Tags tags, double amount) {
        if (registry == null || amount <= 0D) {
            return;
        }
        Counter.builder(name)
                .tags(tags)
                .register(registry)
                .increment(amount);
    }

    private void recordTimer(String name, Tags tags, long latencyMs) {
        if (registry == null) {
            return;
        }
        Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0L, latencyMs)));
    }

    private String modelTag(String credentialSource, String model) {
        // User-supplied credentials can name an arbitrary model string per request; using it as a raw
        // tag would grow Prometheus series without bound. Platform models are operator-controlled and
        // bounded, so keep those and collapse every user-keyed model into a single "custom" bucket.
        // The exact model still lives in the DB telemetry, where cardinality is free.
        if (AgentUsageTelemetryService.USER_KEY.equalsIgnoreCase(StringUtils.trimToEmpty(credentialSource))) {
            return "custom";
        }
        return lowCardinalityTag(model);
    }

    private String lowCardinalityTag(String value) {
        String normalized = StringUtils.defaultIfBlank(value, UNKNOWN)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_");
        // Metrics tags must not carry user/run/request IDs or raw prompt/canvas content.
        return StringUtils.left(StringUtils.defaultIfBlank(normalized, UNKNOWN), MAX_TAG_VALUE_LENGTH);
    }
}
