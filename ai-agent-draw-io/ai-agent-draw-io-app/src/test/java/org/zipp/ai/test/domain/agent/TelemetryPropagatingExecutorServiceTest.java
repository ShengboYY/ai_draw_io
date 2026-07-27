package org.zipp.ai.test.domain.agent;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.TelemetryPropagatingExecutorService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryPropagatingExecutorServiceTest {

    private static AgentUsageTelemetryContext.RunContext run(String runId) {
        return new AgentUsageTelemetryContext.RunContext(
                runId, "request-1", "diagram-1", "owner-1", "300000", "chat",
                "PLATFORM", null, "openai", "unknown", "visual_observation");
    }

    @Test
    void pooledTaskSeesTheSubmittingThreadsRun() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ExecutorService propagating = TelemetryPropagatingExecutorService.wrap(pool);
            AtomicReference<AgentUsageTelemetryContext.RunContext> observed = new AtomicReference<>();

            try (AgentUsageTelemetryContext.Scope ignored =
                         AgentUsageTelemetryContext.bind(run("run-1"))) {
                propagating.submit(() ->
                        observed.set(AgentUsageTelemetryContext.current().orElse(null))).get();
            }

            assertNotNull(observed.get());
            assertEquals("run-1", observed.get().runId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void poolThreadIsClearedSoALaterTaskCannotInheritAForeignRun() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ExecutorService propagating = TelemetryPropagatingExecutorService.wrap(pool);
            try (AgentUsageTelemetryContext.Scope ignored =
                         AgentUsageTelemetryContext.bind(run("run-1"))) {
                propagating.submit(() -> { }).get();
            }

            AtomicReference<AgentUsageTelemetryContext.RunContext> observed = new AtomicReference<>();
            propagating.submit(() ->
                    observed.set(AgentUsageTelemetryContext.current().orElse(null))).get();

            assertNull(observed.get());
            assertTrue(pool.shutdownNow().isEmpty());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void wrappingIsIdempotent() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ExecutorService once = TelemetryPropagatingExecutorService.wrap(pool);
            assertSame(once, TelemetryPropagatingExecutorService.wrap(once));
        } finally {
            pool.shutdownNow();
        }
    }
}
