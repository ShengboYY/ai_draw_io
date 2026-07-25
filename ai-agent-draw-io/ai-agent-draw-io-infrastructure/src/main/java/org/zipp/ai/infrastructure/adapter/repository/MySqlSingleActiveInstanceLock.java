package org.zipp.ai.infrastructure.adapter.repository;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.InstanceBootId;
import org.zipp.ai.application.turn.InstanceLockOutcome;
import org.zipp.ai.application.turn.SingleActiveInstanceLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Holds MySQL GET_LOCK on a dedicated connection until the serving instance exits. */
@Repository
public class MySqlSingleActiveInstanceLock implements SingleActiveInstanceLock {
    private static final String LOCK_NAME = "ai_draw_io:turn_engine:single_active";

    private final DataSource dataSource;
    private final Object monitor = new Object();
    private Connection heldConnection;
    private InstanceBootId holder;

    public MySqlSingleActiveInstanceLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public InstanceLockOutcome acquire(InstanceBootId bootId) {
        Objects.requireNonNull(bootId, "bootId");
        synchronized (monitor) {
            if (heldConnection != null) {
                if (isConnectionAlive(heldConnection)) {
                    return bootId.equals(holder)
                            ? InstanceLockOutcome.ACQUIRED
                            : InstanceLockOutcome.ALREADY_HELD;
                }
                closeHeldConnection();
            }
            try {
                Connection connection = dataSource.getConnection();
                try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                    statement.setString(1, LOCK_NAME);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1) {
                            connection.close();
                            return InstanceLockOutcome.ALREADY_HELD;
                        }
                    }
                }
                heldConnection = connection;
                holder = bootId;
                return InstanceLockOutcome.ACQUIRED;
            } catch (SQLException e) {
                throw new IllegalStateException("TURN_INSTANCE_LOCK_UNAVAILABLE", e);
            }
        }
    }

    @Override
    public boolean isHeld(InstanceBootId bootId) {
        synchronized (monitor) {
            return bootId != null && bootId.equals(holder)
                    && heldConnection != null && isConnectionAlive(heldConnection);
        }
    }

    @PreDestroy
    public void close() {
        synchronized (monitor) {
            closeHeldConnection();
        }
    }

    private boolean isConnectionAlive(Connection connection) {
        try {
            return !connection.isClosed() && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void closeHeldConnection() {
        if (heldConnection == null) {
            return;
        }
        try (PreparedStatement statement = heldConnection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.execute();
        } catch (SQLException ignored) {
            // Closing the dedicated connection also releases the MySQL advisory lock.
        }
        try {
            heldConnection.close();
        } catch (SQLException ignored) {
            // The process is already relinquishing the connection.
        } finally {
            heldConnection = null;
            holder = null;
        }
    }
}
