package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Instant;
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
