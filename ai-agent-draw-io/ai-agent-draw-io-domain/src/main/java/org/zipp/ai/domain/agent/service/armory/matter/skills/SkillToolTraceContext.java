package org.zipp.ai.domain.agent.service.armory.matter.skills;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * Thread-local trace metadata for one ADK tool call.
 */
public final class SkillToolTraceContext {

    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private SkillToolTraceContext() {
    }

    public static Scope bind(String traceId, String sessionId, String invocationId) {
        Trace previous = CURRENT.get();
        CURRENT.set(new Trace(
                StringUtils.trimToEmpty(traceId),
                StringUtils.trimToEmpty(sessionId),
                StringUtils.trimToEmpty(invocationId)));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<Trace> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public record Trace(String traceId, String sessionId, String invocationId) {
    }
}
