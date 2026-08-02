package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionLease;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorkPort;

import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlAutoMemoryAdapterMaintenanceTest {
    @Test
    void purgeIsAtomicBoundedAndCannotTargetUserControlledMemory() {
        Instant cutoff = Instant.parse("2026-05-03T00:00:00Z");
        AtomicBoolean called = new AtomicBoolean();
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if (!"update".equals(method.getName())) {
                        return defaultValue(method.getReturnType());
                    }
                    String sql = (String) args[0];
                    Object[] parameters = (Object[]) args[1];
                    assertTrue(sql.contains("status = 'OBSERVED'"));
                    assertTrue(sql.contains("is_explicit = 0"));
                    assertTrue(sql.contains("updated_at < ?"));
                    assertTrue(sql.contains("ORDER BY updated_at, memory_id"));
                    assertTrue(sql.contains("LIMIT ?"));
                    assertFalse(sql.contains("status = 'ACTIVE'"));
                    assertFalse(sql.contains("status = 'DISABLED'"));
                    assertEquals(Timestamp.from(cutoff), parameters[0]);
                    assertEquals(25, parameters[1]);
                    called.set(true);
                    return 4;
                });

        int purged = new MySqlAutoMemoryAdapter(jdbc).purgeStaleObserved(cutoff, 25);

        assertEquals(4, purged);
        assertTrue(called.get());
    }

    @Test
    void enabledProjectionQueuesEveryPurgedIdentityInTheSameBoundedPath() {
        Instant cutoff = Instant.parse("2026-05-03T00:00:00Z");
        List<String> deleted = new ArrayList<>();
        JdbcOperations jdbc = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                (proxy, method, args) -> {
                    if ("queryForList".equals(method.getName())) {
                        String sql = (String) args[0];
                        assertTrue(sql.contains("FOR UPDATE"));
                        assertTrue(sql.contains("status = 'OBSERVED'"));
                        return List.of("memory-1", "memory-2");
                    }
                    if ("update".equals(method.getName())) {
                        String sql = (String) args[0];
                        Object[] parameters = (Object[]) args[1];
                        assertTrue(sql.contains("memory_id = ?"));
                        assertTrue(sql.contains("status = 'OBSERVED'"));
                        deleted.add((String) parameters[0]);
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        RecordingProjectionWork projection = new RecordingProjectionWork();

        int purged = new MySqlAutoMemoryAdapter(jdbc, projection)
                .purgeStaleObserved(cutoff, 25);

        assertEquals(2, purged);
        assertEquals(List.of("memory-1", "memory-2"), deleted);
        assertEquals(deleted, projection.enqueued);
    }

    private static final class RecordingProjectionWork
            implements AutoMemoryVectorProjectionWorkPort {
        private final List<String> enqueued = new ArrayList<>();

        @Override
        public void enqueue(String memoryId) {
            enqueued.add(memoryId);
        }

        @Override
        public Optional<AutoMemoryVectorProjectionLease> claim(
                String workerId,
                Instant now,
                Duration leaseDuration
        ) {
            return Optional.empty();
        }

        @Override
        public boolean complete(AutoMemoryVectorProjectionLease lease, Instant now) {
            return false;
        }

        @Override
        public boolean retry(
                AutoMemoryVectorProjectionLease lease,
                String errorCode,
                Instant availableAt,
                Instant now
        ) {
            return false;
        }
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
}
