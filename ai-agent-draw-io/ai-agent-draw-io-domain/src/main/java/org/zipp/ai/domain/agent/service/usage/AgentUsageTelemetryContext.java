package org.zipp.ai.domain.agent.service.usage;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AgentUsageTelemetryContext {

    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();
    private static final ConcurrentMap<String, RunContext> SESSION_CONTEXTS = new ConcurrentHashMap<>();

    private AgentUsageTelemetryContext() {
    }

    public static Scope bind(RunContext context) {
        RunContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Scope enterPhase(String phase) {
        RunContext current = CURRENT.get();
        if (current == null) {
            return () -> { };
        }
        return bind(current.withPhase(phase));
    }

    public static Optional<RunContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<RunContext> resolve(String sessionId) {
        RunContext current = CURRENT.get();
        if (current != null) {
            return Optional.of(current);
        }
        if (StringUtils.isBlank(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(SESSION_CONTEXTS.get(sessionId));
    }

    public static void bindSession(String sessionId, RunContext context) {
        if (StringUtils.isNotBlank(sessionId) && context != null) {
            SESSION_CONTEXTS.put(sessionId, context);
        }
    }

    public static void inheritCurrentToSession(String sessionId) {
        current().ifPresent(context -> bindSession(sessionId, context));
    }

    public static void clearSession(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            SESSION_CONTEXTS.remove(sessionId);
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record RunContext(
            String runId,
            String userId,
            String agentId,
            String requestType,
            String credentialSource,
            String modelCredentialId,
            String provider,
            String model,
            String phase
    ) {
        public RunContext withPhase(String nextPhase) {
            return new RunContext(runId, userId, agentId, requestType, credentialSource,
                    modelCredentialId, provider, model, nextPhase);
        }

        public RunContext withProviderModel(String nextProvider, String nextModel) {
            return new RunContext(runId, userId, agentId, requestType, credentialSource,
                    modelCredentialId, nextProvider, nextModel, phase);
        }
    }
}
