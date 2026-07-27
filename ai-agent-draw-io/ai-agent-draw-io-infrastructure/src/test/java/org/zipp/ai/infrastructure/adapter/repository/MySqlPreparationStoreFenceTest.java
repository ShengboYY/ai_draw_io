package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlPreparationStoreFenceTest {

    @Test
    void staleAttemptCannotWriteEvidencePreparation() {
        JdbcFixture fixture = jdbc(false);
        MySqlEvidencePreparationStore store = new MySqlEvidencePreparationStore(fixture.jdbc());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.save(
                attempt(), "evidence-ref", identity(), "snapshot-ref",
                "b".repeat(64), List.of()));

        assertEquals("EVIDENCE_PREPARATION_FENCE_NOT_ACTIVE", failure.getMessage());
        assertEquals(0, fixture.updates().get());
    }

    @Test
    void staleAttemptCannotWriteDirectPreparation() {
        JdbcFixture fixture = jdbc(false);
        MySqlDirectPreparationStore store = new MySqlDirectPreparationStore(fixture.jdbc());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.save(
                attempt(), "direct-ref", identity(), "snapshot-ref",
                "observation-ref", "<mxGraphModel/>"));

        assertEquals("DIRECT_PREPARATION_FENCE_NOT_ACTIVE", failure.getMessage());
        assertEquals(0, fixture.updates().get());
    }

    @Test
    void currentAttemptCanWriteBothPreparationTypes() {
        JdbcFixture fixture = jdbc(true);

        new MySqlEvidencePreparationStore(fixture.jdbc()).save(
                attempt(), "evidence-ref", identity(), "snapshot-ref",
                "b".repeat(64), List.of());
        new MySqlDirectPreparationStore(fixture.jdbc()).save(
                attempt(), "direct-ref", identity(), "snapshot-ref",
                "observation-ref", "<mxGraphModel/>");

        assertEquals(2, fixture.updates().get());
    }

    private JdbcFixture jdbc(boolean active) {
        AtomicInteger updates = new AtomicInteger();
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())) {
                        return active ? List.of("attempt-1") : List.of();
                    }
                    if ("update".equals(method.getName())) {
                        updates.incrementAndGet();
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        return new JdbcFixture(jdbc, updates);
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, Instant.now(), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.ALL_V2, "{}", "policy"));
    }

    private SourcePlanIdentity identity() {
        return new SourcePlanIdentity(
                new PlanningLineageFingerprint("a".repeat(64)),
                "c".repeat(64));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private record JdbcFixture(JdbcOperations jdbc, AtomicInteger updates) {
    }
}
