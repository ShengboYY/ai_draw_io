package org.zipp.ai.domain.agent.service.usage;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary;
import org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry;
import org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry;
import org.zipp.ai.types.util.SecretLogSanitizer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@Service
public class AgentUsageTelemetryService {

    public static final String PLATFORM = "PLATFORM";
    public static final String USER_KEY = "USER_KEY";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";

    private final IAgentUsageTelemetryStore telemetryStore;
    private final Clock clock;

    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore) {
        this(telemetryStore, Clock.systemUTC());
    }

    public AgentUsageTelemetryService(IAgentUsageTelemetryStore telemetryStore, Clock clock) {
        this.telemetryStore = telemetryStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public RunScope startRun(String userId,
                             String agentId,
                             String sessionId,
                             String requestType,
                             String credentialSource,
                             String modelCredentialId,
                             String provider,
                             String model) {
        String runId = "aru_" + UUID.randomUUID();
        Instant startedAt = clock.instant();
        AgentUsageTelemetryContext.RunContext context = new AgentUsageTelemetryContext.RunContext(
                runId,
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
                .id(runId)
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

    public RunScope withProviderModel(RunScope runScope, String provider, String model) {
        if (runScope == null) {
            return null;
        }
        return new RunScope(runScope.context.withProviderModel(
                StringUtils.defaultIfBlank(provider, runScope.context.provider()),
                StringUtils.defaultIfBlank(model, runScope.context.model())), runScope.startedAt);
    }

    public void bindSession(String sessionId, RunScope runScope) {
        if (runScope != null) {
            AgentUsageTelemetryContext.bindSession(sessionId, runScope.context);
        }
    }

    public void completeRun(RunScope runScope, Throwable error) {
        if (runScope == null) {
            return;
        }
        Instant completedAt = clock.instant();
        safeStore(() -> telemetryStore.completeRun(
                runScope.context.runId(),
                error == null ? SUCCESS : FAILED,
                errorClass(error),
                completedAt,
                latencyMs(runScope.startedAt, completedAt)), runScope.context.userId());
    }

    public <T> T recordStep(String phase, Callable<T> action) throws Exception {
        StepScope step = startStep(phase);
        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.enterPhase(phase)) {
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
        return new StepScope(current.get(), phase, clock.instant());
    }

    public void completeStep(StepScope stepScope, Throwable error) {
        if (stepScope == null) {
            return;
        }
        Instant completedAt = clock.instant();
        safeStore(() -> telemetryStore.insertStep(AgentRunStepTelemetry.builder()
                .id("ars_" + UUID.randomUUID())
                .runId(stepScope.context.runId())
                .userId(stepScope.context.userId())
                .phase(stepScope.phase)
                .status(error == null ? SUCCESS : FAILED)
                .errorClass(errorClass(error))
                .startedAt(stepScope.startedAt)
                .completedAt(completedAt)
                .latencyMs(latencyMs(stepScope.startedAt, completedAt))
                .build()), stepScope.context.userId());
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
                .userId(context.userId())
                .phase(StringUtils.defaultIfBlank(phase, context.phase()))
                .toolName(toolName)
                .status(error == null ? SUCCESS : FAILED)
                .errorClass(errorClass(error))
                .startedAt(startedAt)
                .completedAt(completedAt)
                .latencyMs(latencyMs)
                .build()), context.userId());
    }

    public AgentUsageSummary summarizeForUser(String userId) {
        if (telemetryStore == null || StringUtils.isBlank(userId)) {
            return AgentUsageSummary.empty();
        }
        return telemetryStore.summarizeForUser(userId);
    }

    private void safeStore(Runnable operation, String userId) {
        if (telemetryStore == null || operation == null) {
            return;
        }
        try {
            operation.run();
        } catch (Exception e) {
            // Telemetry must never break the user-visible request path.
            org.slf4j.LoggerFactory.getLogger(AgentUsageTelemetryService.class)
                    .warn("Agent usage telemetry write failed. userId:{}", SecretLogSanitizer.maskCapability(userId), e);
        }
    }

    private String normalizeCredentialSource(String credentialSource) {
        return USER_KEY.equals(credentialSource) ? USER_KEY : PLATFORM;
    }

    private String errorClass(Throwable error) {
        return error == null ? null : error.getClass().getSimpleName();
    }

    private long latencyMs(Instant startedAt, Instant completedAt) {
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
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
        private final String phase;
        private final Instant startedAt;

        private StepScope(AgentUsageTelemetryContext.RunContext context, String phase, Instant startedAt) {
            this.context = context;
            this.phase = phase;
            this.startedAt = startedAt;
        }
    }
}
