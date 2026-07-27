package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.InstanceBootId;
import org.zipp.ai.application.turn.InstanceLockOutcome;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlSingleActiveInstanceLockTest {

    @Test
    void retainsOneConnectionAndAllowsOnlyTheSameBootToReenter() {
        DatabaseStub database = new DatabaseStub(1, false);
        MySqlSingleActiveInstanceLock lock = new MySqlSingleActiveInstanceLock(database.dataSource());
        InstanceBootId first = new InstanceBootId("boot-1");
        InstanceBootId second = new InstanceBootId("boot-2");

        assertEquals(InstanceLockOutcome.ACQUIRED, lock.acquire(first));
        assertEquals(InstanceLockOutcome.ACQUIRED, lock.acquire(first));
        assertEquals(InstanceLockOutcome.ALREADY_HELD, lock.acquire(second));
        assertEquals(1, database.connectionCount.get());
        assertTrue(lock.isHeld(first));
        assertFalse(database.connection.closed);

        lock.close();

        assertTrue(database.connection.releaseCalled);
        assertTrue(database.connection.closed);
        assertFalse(lock.isHeld(first));
    }

    @Test
    void closesConnectionWhenAnotherInstanceHoldsTheAdvisoryLock() {
        DatabaseStub database = new DatabaseStub(0, false);
        MySqlSingleActiveInstanceLock lock = new MySqlSingleActiveInstanceLock(database.dataSource());

        assertEquals(InstanceLockOutcome.ALREADY_HELD,
                lock.acquire(new InstanceBootId("boot-1")));

        assertTrue(database.connection.closed);
        assertFalse(lock.isHeld(new InstanceBootId("boot-1")));
    }

    @Test
    void closesConnectionWhenGetLockFailsWithSqlError() {
        DatabaseStub database = new DatabaseStub(1, true);
        MySqlSingleActiveInstanceLock lock = new MySqlSingleActiveInstanceLock(database.dataSource());

        assertThrows(IllegalStateException.class,
                () -> lock.acquire(new InstanceBootId("boot-1")));

        assertTrue(database.connection.closed);
        assertFalse(lock.isHeld(new InstanceBootId("boot-1")));
    }

    private static final class DatabaseStub {
        private final int lockResult;
        private final boolean failGetLock;
        private final AtomicInteger connectionCount = new AtomicInteger();
        private final ConnectionStub connection = new ConnectionStub();

        private DatabaseStub(int lockResult, boolean failGetLock) {
            this.lockResult = lockResult;
            this.failGetLock = failGetLock;
        }

        private DataSource dataSource() {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("getConnection".equals(method.getName())) {
                    connectionCount.incrementAndGet();
                    return connection.proxy(lockResult, failGetLock);
                }
                return defaultValue(method.getReturnType());
            };
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, handler);
        }
    }

    private static final class ConnectionStub {
        private boolean closed;
        private boolean releaseCalled;

        private Connection proxy(int lockResult, boolean failGetLock) {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName())) {
                    return preparedStatement(lockResult, failGetLock, (String) args[0]);
                }
                if ("isClosed".equals(method.getName())) {
                    return closed;
                }
                if ("isValid".equals(method.getName())) {
                    return !closed;
                }
                if ("close".equals(method.getName())) {
                    closed = true;
                    return null;
                }
                return defaultValue(method.getReturnType());
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement preparedStatement(int lockResult, boolean failGetLock, String sql) {
            InvocationHandler handler = (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    if (failGetLock) {
                        throw new SQLException("get lock unavailable");
                    }
                    return resultSet(lockResult);
                }
                if ("execute".equals(method.getName())) {
                    if (sql.contains("RELEASE_LOCK")) {
                        releaseCalled = true;
                    }
                    return true;
                }
                return defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, handler);
        }

        private ResultSet resultSet(int lockResult) {
            InvocationHandler handler = new InvocationHandler() {
                private boolean first = true;

                @Override
                public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                    if ("next".equals(method.getName())) {
                        boolean result = first;
                        first = false;
                        return result;
                    }
                    if ("getInt".equals(method.getName())) {
                        return lockResult;
                    }
                    return defaultValue(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, handler);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
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
