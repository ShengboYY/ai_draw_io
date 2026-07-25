package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnLifecycleTraceEvent;
import org.zipp.ai.application.turn.TurnLifecycleTraceType;
import org.zipp.ai.application.turn.TurnStatus;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlTurnLifecycleTraceAdapterTest {

    @Test
    void persistsOnlyTheRedactedTraceColumns() {
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName())) {
                        sql.set((String) args[0]);
                        arguments.set((Object[]) args[1]);
                        return 1;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        TurnLifecycleTraceEvent event = new TurnLifecycleTraceEvent(
                TurnLifecycleTraceType.ATTEMPT_COMPLETED,
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "attempt-1",
                2,
                "policy-hash",
                "input-digest",
                null,
                "DONE",
                TurnStatus.COMPLETED,
                Instant.parse("2026-07-26T00:00:00Z"));

        new MySqlTurnLifecycleTraceAdapter(jdbc).record(event);

        assertTrue(sql.get().contains("turn_lifecycle_trace"));
        assertEquals(12, arguments.get().length);
        assertTrue(Arrays.stream(arguments.get()).noneMatch("user content"::equals));
        assertEquals("ATTEMPT_COMPLETED", arguments.get()[3]);
        assertEquals("DONE", arguments.get()[9]);
        assertEquals("COMPLETED", arguments.get()[10]);
        assertEquals(Instant.parse("2026-07-26T00:00:00Z"),
                ((java.sql.Timestamp) arguments.get()[11]).toInstant());
    }
}
