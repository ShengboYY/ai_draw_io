package org.zipp.ai.domain.agent.service.usage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary;
import org.zipp.ai.types.util.SecretLogSanitizer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import com.alibaba.fastjson.JSON;

@Service
public class AgentUsageTelemetryService {

    public static final String PLATFORM = "PLATFORM";
    public static final String USER_KEY = "USER_KEY";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final int MAX_METADATA_JSON_LENGTH = 2_000;
    private static final int TELEMETRY_QUEUE_CAPACITY = 10_000;

    private final IAgentUsageTelemetryStore telemetryStore;
    private final Clock clock;
    private final TelemetryWriteExecutor writeExecutor;
    private final AgentTelemetryMetrics metrics;
    private final AtomicLong droppedTelemetryWrites = new AtomicLong();

    @Autowired
    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore,
                                      AgentTelemetryMetrics metrics) {
        this(telemetryStore, Clock.systemUTC(), TelemetryWriteExecutor.async(TELEMETRY_QUEUE_CAPACITY), metrics);
    }

    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore) {
        this(telemetryStore, Clock.systemUTC(), TelemetryWriteExecutor.async(TELEMETRY_QUEUE_CAPACITY), AgentTelemetryMetrics.noop());
    }

    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore, Clock clock) {
        this(telemetryStore, clock, TelemetryWriteExecutor.direct(), AgentTelemetryMetrics.noop());
    }

    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore,
                                      Clock clock,
                                      TelemetryWriteExecutor writeExecutor) {
        this(telemetryStore, clock, writeExecutor, AgentTelemetryMetrics.noop());
    }

    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore,
                                      Clock clock,
                                      TelemetryWriteExecutor writeExecutor,
                                      AgentTelemetryMetrics metrics) {
        this.telemetryStore = telemetryStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.writeExecutor = writeExecutor == null ? TelemetryWriteExecutor.direct() : writeExecutor;
        this.metrics = metrics == null ? AgentTelemetryMetrics.noop() : metrics;
        this.metrics.registerTelemetryWriter(this.writeExecutor);
    }

    public RunScope startRun(String userId,
                             String agentId,
                             String sessionId,
                             String requestType,
                             String credentialSource,
                             String modelCredentialId,
                             String provider,
                             String model) {
        return startRun(null, null, userId, agentId, sessionId, requestType,
                credentialSource, modelCredentialId, provider, model);
    }

    public RunScope startRun(String runId,
                             String requestId,
                             String userId,
                             String agentId,
                             String sessionId,
                             String requestType,
                             String credentialSource,
                             String modelCredentialId,
                             String provider,
                             String model) {
        String resolvedRunId = normalizeRunId(runId);
        Instant startedAt = clock.instant();
        AgentUsageTelemetryContext.RunContext context = new AgentUsageTelemetryContext.RunContext(
                resolvedRunId,
                blankToNull(requestId),
                userId,
                agentId,
                requestType,
                normalizeCredentialSource(credentialSource),
                blankToNull(modelCredentialId),
                StringUtils.defaultIfBlank(provider, "openai"),
                StringUtils.defaultIfBlank(model, "unknown"),
                "request"
        );
        safeStore(() -> telemetryStore.insertRun(AgentRunTelemetry.builder()
                .id(resolvedRunId)
                .requestId(context.requestId())
                .userId(userId)
                .agentId(agentId)
                .sessionId(blankToNull(sessionId))
                .requestType(requestType)
                .credentialSource(context.credentialSource())
                .modelCredentialId(context.modelCredentialId())
                .status(RUNNING)
                .startedAt(startedAt)
                .build()), userId);
        return new RunScope(context, startedAt);
    }

    public String newRunId() {
        return "aru_" + UUID.randomUUID();
    }

    public RunScope withProviderModel(RunScope runScope, String provider, String model) {
        if (runScope == null) {
            return null;
        }
        return new RunScope(runScope.context.withProviderModel(
                StringUtils.defaultIfBlank(provider, runScope.context.provider()),
                StringUtils.defaultIfBlank(model, runScope.context.model())), runScope.startedAt);
    }

    public void completeRun(RunScope runScope, Throwable error) {
        if (runScope == null) {
            return;
        }
        Instant completedAt = clock.instant();
        long latencyMs = latencyMs(runScope.startedAt, completedAt);
        safeStore(() -> telemetryStore.completeRun(
                runScope.context.runId(),
                error == null ? SUCCESS : FAILED,
                errorClass(error),
                completedAt,
                latencyMs), runScope.context.userId());
        metrics.recordRun(runScope.context.requestType(),
                runScope.context.credentialSource(),
                error == null ? SUCCESS : FAILED,
                latencyMs);
    }

    public <T> T recordStep(String phase, Callable<T> action) throws Exception {
        StepScope step = startStep(phase);
        // Bind the step-scoped context (spanId = step id) so LLM/tool calls made inside the
        // step parent onto it. When there is no active run context, fall back to a phase-only
        // scope so the action still runs.
        AgentUsageTelemetryContext.Scope scope = step == null
                ? AgentUsageTelemetryContext.enterPhase(phase)
                : AgentUsageTelemetryContext.bind(step.stepContext);
        try (AgentUsageTelemetryContext.Scope ignored = scope) {
            T result = action.call();
            completeStep(step, null);
            return result;
        } catch (Exception e) {
            completeStep(step, e);
            throw e;
        }
    }

    public StepScope startStep(String phase) {
        Optional<AgentUsageTelemetryContext.RunContext> current = AgentUsageTelemetryContext.current();
        if (current.isEmpty()) {
            return null;
        }
        AgentUsageTelemetryContext.RunContext parent = current.get();
        String stepId = "ars_" + UUID.randomUUID();
        AgentUsageTelemetryContext.RunContext stepContext = parent.withPhase(phase).withSpan(stepId);
        return new StepScope(parent, stepContext, stepId, phase, clock.instant());
    }

    public void completeStep(StepScope stepScope, Throwable error) {
        if (stepScope == null) {
            return;
        }
        Instant completedAt = clock.instant();
        safeStore(() -> telemetryStore.insertStep(AgentRunStepTelemetry.builder()
                .id(stepScope.stepId)
                .runId(stepScope.context.runId())
                .parentId(stepScope.context.spanId())
                .userId(stepScope.context.userId())
                .phase(stepScope.phase)
                .status(error == null ? SUCCESS : FAILED)
                .errorClass(errorClass(error))
                .startedAt(stepScope.startedAt)
                .completedAt(completedAt)
                .latencyMs(latencyMs(stepScope.startedAt, completedAt))
                .build()), stepScope.context.userId());
    }

    public void recordTraceEvent(String eventType, String phase, String status, Map<String, ?> metadata) {
        AgentUsageTelemetryContext.current().ifPresent(context ->
                recordTraceEvent(context, eventType, phase, status, metadata));
    }

    public void recordTraceEvent(AgentUsageTelemetryContext.RunContext context,
                                 String eventType,
                                 String phase,
                                 String status,
                                 Map<String, ?> metadata) {
        if (context == null || StringUtils.isBlank(eventType)) {
            return;
        }
        Instant occurredAt = clock.instant();
        long sequenceNo = context.nextSequenceNo();
        String metadataJson = sanitizedMetadataJson(metadata);
        safeStore(() -> telemetryStore.insertTraceEvent(AgentTraceEvent.builder()
                .id("ate_" + UUID.randomUUID())
                .runId(context.runId())
                .parentId(parentSpanId(context))
                .requestId(context.requestId())
                .userId(context.userId())
                .sequenceNo(sequenceNo)
                .eventType(StringUtils.left(eventType, 64))
                .phase(StringUtils.defaultIfBlank(StringUtils.left(phase, 32), context.phase()))
                .status(StringUtils.defaultIfBlank(StringUtils.left(status, 24), SUCCESS))
                .metadataJson(metadataJson)
                .occurredAt(occurredAt)
                .build()), context.userId());
    }

    public void recordLlmCall(String phase,
                              String provider,
                              String model,
                              Long latencyMs,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens,
                              Throwable error) {
        AgentUsageTelemetryContext.current().ifPresent(context ->
                recordLlmCall(context, phase, provider, model, latencyMs, promptTokens, completionTokens, totalTokens, error));
    }

    public void recordLlmCall(AgentUsageTelemetryContext.RunContext context,
                              String phase,
                              String provider,
                              String model,
                              Long latencyMs,
                              Integer promptTokens,
                              Integer completionTokens,
                              Integer totalTokens,
                              Throwable error) {
        if (context == null) {
            return;
        }
        Instant completedAt = clock.instant();
        Instant startedAt = latencyMs == null ? completedAt : completedAt.minusMillis(Math.max(0, latencyMs));
        safeStore(() -> telemetryStore.insertLlmCall(LlmCallTelemetry.builder()
                .id("alc_" + UUID.randomUUID())
                .runId(context.runId())
                .parentId(parentSpanId(context))
                .userId(context.userId())
                .phase(StringUtils.defaultIfBlank(phase, context.phase()))
                .provider(StringUtils.defaultIfBlank(provider, context.provider()))
                .model(StringUtils.defaultIfBlank(model, context.model()))
                .credentialSource(context.credentialSource())
                .modelCredentialId(context.modelCredentialId())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .status(error == null ? SUCCESS : FAILED)
                .errorClass(errorClass(error))
                .startedAt(startedAt)
                .completedAt(completedAt)
                .latencyMs(latencyMs)
                .build()), context.userId());
        metrics.recordLlmCall(
                StringUtils.defaultIfBlank(phase, context.phase()),
                StringUtils.defaultIfBlank(provider, context.provider()),
                StringUtils.defaultIfBlank(model, context.model()),
                context.credentialSource(),
                error == null ? SUCCESS : FAILED,
                latencyMs,
                promptTokens,
                completionTokens,
                totalTokens);
    }

    public void recordToolCall(AgentUsageTelemetryContext.RunContext context,
                               String phase,
                               String toolName,
                               Long latencyMs,
                               Throwable error) {
        if (context == null || StringUtils.isBlank(toolName)) {
            return;
        }
        Instant completedAt = clock.instant();
        Instant startedAt = latencyMs == null ? completedAt : completedAt.minusMillis(Math.max(0, latencyMs));
        safeStore(() -> telemetryStore.insertToolCall(ToolCallTelemetry.builder()
                .id("atc_" + UUID.randomUUID())
                .runId(context.runId())
                .parentId(parentSpanId(context))
                .userId(context.userId())
                .phase(StringUtils.defaultIfBlank(phase, context.phase()))
                .toolName(toolName)
                .status(error == null ? SUCCESS : FAILED)
                .errorClass(errorClass(error))
                .startedAt(startedAt)
                .completedAt(completedAt)
                .latencyMs(latencyMs)
                .build()), context.userId());
        metrics.recordToolCall(
                StringUtils.defaultIfBlank(phase, context.phase()),
                toolName,
                error == null ? SUCCESS : FAILED,
                latencyMs);
    }

    public AgentUsageSummary summarizeForUser(String userId) {
        if (telemetryStore == null || StringUtils.isBlank(userId)) {
            return AgentUsageSummary.empty();
        }
        return telemetryStore.summarizeForUser(userId);
    }

    public AdminUsageSummary summarizeGlobal() {
        if (telemetryStore == null) {
            return AdminUsageSummary.empty();
        }
        return telemetryStore.summarizeGlobal();
    }

    public List<UsageDimensionSummary> summarizeByProviderModelCredentialSource() {
        if (telemetryStore == null) {
            return List.of();
        }
        return telemetryStore.summarizeByProviderModelCredentialSource();
    }

    public Optional<AgentRunDetail> findRunDetail(String runId) {
        if (telemetryStore == null || StringUtils.isBlank(runId)) {
            return Optional.empty();
        }
        return telemetryStore.findRunDetail(runId);
    }

    public List<AgentRunTelemetry> listRuns(String status, String userId, String agentId, Integer limit, Integer offset) {
        if (telemetryStore == null) {
            return List.of();
        }
        int boundedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        int boundedOffset = offset == null ? 0 : Math.max(0, offset);
        return telemetryStore.listRuns(
                StringUtils.trimToNull(status),
                StringUtils.trimToNull(userId),
                StringUtils.trimToNull(agentId),
                boundedLimit,
                boundedOffset);
    }

    public int deleteTelemetryBefore(Instant cutoff) {
        if (telemetryStore == null || cutoff == null) {
            return 0;
        }
        return telemetryStore.deleteTelemetryBefore(cutoff);
    }

    private void safeStore(Runnable operation, String userId) {
        if (telemetryStore == null || operation == null) {
            return;
        }
        try {
            writeExecutor.execute(() -> executeStoreOperation(operation, userId));
        } catch (RejectedExecutionException e) {
            long dropped = droppedTelemetryWrites.incrementAndGet();
            metrics.recordTelemetryWriteDropped();
            org.slf4j.LoggerFactory.getLogger(AgentUsageTelemetryService.class)
                    .warn("Agent usage telemetry queue full; dropped write count:{} userId:{}",
                            dropped, SecretLogSanitizer.maskCapability(userId));
        }
    }

    private void executeStoreOperation(Runnable operation, String userId) {
        try {
            operation.run();
        } catch (Exception e) {
            // Telemetry must never break the user-visible request path.
            metrics.recordTelemetryWriteDropped();
            org.slf4j.LoggerFactory.getLogger(AgentUsageTelemetryService.class)
                    .warn("Agent usage telemetry write failed. userId:{}", SecretLogSanitizer.maskCapability(userId), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        writeExecutor.shutdown();
    }

    private String normalizeCredentialSource(String credentialSource) {
        return USER_KEY.equals(credentialSource) ? USER_KEY : PLATFORM;
    }

    private String errorClass(Throwable error) {
        return error == null ? null : error.getClass().getSimpleName();
    }

    /** Parent span for a call: the active span (a step), or the run root when no step is in scope. */
    private String parentSpanId(AgentUsageTelemetryContext.RunContext context) {
        return StringUtils.defaultIfBlank(context.spanId(), context.runId());
    }

    private long latencyMs(Instant startedAt, Instant completedAt) {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }

    private String normalizeRunId(String value) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null || trimmed.length() > 64 || !trimmed.startsWith("aru_")) {
            return newRunId();
        }
        return trimmed;
    }

    private String sanitizedMetadataJson(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        String json = JSON.toJSONString(metadata);
        return StringUtils.left(SecretLogSanitizer.sanitize(json), MAX_METADATA_JSON_LENGTH);
    }

    public static class RunScope {
        private final AgentUsageTelemetryContext.RunContext context;
        private final Instant startedAt;

        private RunScope(AgentUsageTelemetryContext.RunContext context, Instant startedAt) {
            this.context = context;
            this.startedAt = startedAt;
        }

        public AgentUsageTelemetryContext.RunContext getContext() {
            return context;
        }
    }

    public static class StepScope {
        private final AgentUsageTelemetryContext.RunContext context;
        private final AgentUsageTelemetryContext.RunContext stepContext;
        private final String stepId;
        private final String phase;
        private final Instant startedAt;

        private StepScope(AgentUsageTelemetryContext.RunContext context,
                          AgentUsageTelemetryContext.RunContext stepContext,
                          String stepId,
                          String phase,
                          Instant startedAt) {
            this.context = context;
            this.stepContext = stepContext;
            this.stepId = stepId;
            this.phase = phase;
            this.startedAt = startedAt;
        }

        /** Context bound while the step runs; its spanId is the step id so child calls parent onto it. */
        public AgentUsageTelemetryContext.RunContext getStepContext() {
            return stepContext;
        }
    }

    public interface TelemetryWriteExecutor {
        void execute(Runnable operation);
        void shutdown();
        default int queueSize() { return 0; }
        default int queueRemainingCapacity() { return Integer.MAX_VALUE; }

        static TelemetryWriteExecutor direct() {
            return new TelemetryWriteExecutor() {
                @Override public void execute(Runnable operation) { operation.run(); }
                @Override public void shutdown() { }
            };
        }

        static TelemetryWriteExecutor async(int capacity) {
            LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(Math.max(1, capacity));
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "agent-usage-telemetry-writer");
                thread.setDaemon(true);
                return thread;
            };
            ExecutorService executor = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, queue, threadFactory,
                    new ThreadPoolExecutor.AbortPolicy());
            return new TelemetryWriteExecutor() {
                @Override public void execute(Runnable operation) { executor.execute(operation); }
                @Override public void shutdown() { executor.shutdown(); }
                @Override public int queueSize() { return queue.size(); }
                @Override public int queueRemainingCapacity() { return queue.remainingCapacity(); }
            };
        }
    }
}
