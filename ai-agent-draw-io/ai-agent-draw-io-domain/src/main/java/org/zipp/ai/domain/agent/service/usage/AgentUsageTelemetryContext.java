package org.zipp.ai.domain.agent.service.usage;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

public final class AgentUsageTelemetryContext {

    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();
    public static final String INVOCATION_STATE_TOKEN_KEY = "zipp.telemetry.runContextToken";
    private static final ConcurrentMap<String, RunContext> TOKEN_CONTEXTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, InvocationBinding> INVOCATION_CONTEXTS = new ConcurrentHashMap<>();

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
        return Optional.empty();
    }

    public static void bindSession(String sessionId, RunContext context) {
    }

    public static void inheritCurrentToSession(String sessionId) {
    }

    public static void clearSession(String sessionId) {
    }

    public static InvocationState newInvocationState(RunContext context) {
        if (context == null) {
            return InvocationState.empty();
        }
        String token = "ait_" + UUID.randomUUID();
        TOKEN_CONTEXTS.put(token, context);
        return new InvocationState(token, Map.of(INVOCATION_STATE_TOKEN_KEY, token));
    }

    public static void registerInvocation(String invocationId, Map<String, Object> state) {
        String id = StringUtils.trimToNull(invocationId);
        Object tokenValue = state == null ? null : state.get(INVOCATION_STATE_TOKEN_KEY);
        String token = tokenValue == null ? null : StringUtils.trimToNull(String.valueOf(tokenValue));
        if (id == null || token == null) {
            return;
        }
        RunContext context = TOKEN_CONTEXTS.get(token);
        if (context != null) {
            INVOCATION_CONTEXTS.put(id, new InvocationBinding(token, context));
        }
    }

    public static Optional<RunContext> resolveInvocation(String invocationId) {
        String id = StringUtils.trimToNull(invocationId);
        if (id == null) {
            return Optional.empty();
        }
        InvocationBinding binding = INVOCATION_CONTEXTS.get(id);
        return binding == null ? Optional.empty() : Optional.of(binding.context());
    }

    public static void clearInvocation(String invocationId) {
        String id = StringUtils.trimToNull(invocationId);
        if (id == null) {
            return;
        }
        InvocationBinding binding = INVOCATION_CONTEXTS.remove(id);
        if (binding != null) {
            TOKEN_CONTEXTS.remove(binding.token());
        }
    }

    public static void clearInvocationToken(String token) {
        String trimmed = StringUtils.trimToNull(token);
        if (trimmed != null) {
            TOKEN_CONTEXTS.remove(trimmed);
            // Close is the final safety net if ADK does not reach afterRunCallback on an error path.
            INVOCATION_CONTEXTS.entrySet().removeIf(entry -> trimmed.equals(entry.getValue().token()));
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record RunContext(
            String runId,
            String requestId,
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
            return new RunContext(runId, requestId, userId, agentId, requestType, credentialSource,
                    modelCredentialId, provider, model, nextPhase);
        }

        public RunContext withProviderModel(String nextProvider, String nextModel) {
            return new RunContext(runId, requestId, userId, agentId, requestType, credentialSource,
                    modelCredentialId, nextProvider, nextModel, phase);
        }
    }

    private record InvocationBinding(String token, RunContext context) {
    }

    public record InvocationState(String token, Map<String, Object> stateDelta) implements AutoCloseable {
        private static InvocationState empty() {
            return new InvocationState(null, Map.of());
        }

        @Override
        public void close() {
            AgentUsageTelemetryContext.clearInvocationToken(token);
        }
    }
}
