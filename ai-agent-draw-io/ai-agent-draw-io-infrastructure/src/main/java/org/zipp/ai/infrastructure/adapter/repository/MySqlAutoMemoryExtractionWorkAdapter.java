package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.memory.AutoMemoryExtractionLease;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorkPort;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnKey;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable lease adapter for model work that must never run inside a Turn terminal transaction. */
@Repository
@Primary
@ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
public class MySqlAutoMemoryExtractionWorkAdapter implements AutoMemoryExtractionWorkPort {
    private static final int MAX_ATTEMPTS = 8;

    private static final String ENQUEUE = """
            INSERT INTO memory_extraction_work (
                work_id, owner_key, source_conversation_id, source_turn_id,
                source_diagram_id, chartbook_id, status, attempt_count,
                available_at, version, created_at, updated_at
            )
            SELECT ?, ?, ?, ?, d.id, d.chartbook_id, 'PENDING', 0,
                   UTC_TIMESTAMP(3), 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
            FROM diagram d
            JOIN turn_execution e
              ON e.owner_key = d.user_id
             AND e.diagram_id = d.id
             AND e.conversation_id = ?
             AND e.turn_id = ?
             AND e.status = 'COMPLETED'
            WHERE d.id = ? AND d.user_id = ? AND d.deleted = 0
            ON DUPLICATE KEY UPDATE work_id = work_id
            """;

    private static final String CLAIM_CANDIDATE = """
            SELECT work_id, version
            FROM memory_extraction_work
            WHERE (status = 'PENDING' AND available_at <= ?)
               OR (status = 'PROCESSING' AND lease_expires_at <= ?)
            ORDER BY available_at, created_at, work_id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    private static final String CLAIM = """
            UPDATE memory_extraction_work
            SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                lease_owner = ?, lease_expires_at = ?, version = version + 1,
                last_error_code = NULL, updated_at = ?
            WHERE work_id = ? AND version = ?
              AND ((status = 'PENDING' AND available_at <= ?)
                OR (status = 'PROCESSING' AND lease_expires_at <= ?))
            """;

    private static final String LOAD = """
            SELECT w.work_id, w.owner_key, w.source_conversation_id, w.source_turn_id,
                   w.source_diagram_id, w.chartbook_id, w.attempt_count, w.version,
                   m.content AS user_content, e.turn_input_binding_json
            FROM memory_extraction_work w
            JOIN turn_execution e
              ON e.owner_key = w.owner_key
             AND e.conversation_id = w.source_conversation_id
             AND e.turn_id = w.source_turn_id
             AND e.status = 'COMPLETED'
            JOIN diagram_conversation_message m
              ON m.id = e.request_message_id
             AND m.user_id = w.owner_key
             AND m.diagram_id = w.source_diagram_id
             AND m.turn_id = w.source_turn_id
             AND m.role = 'user'
             AND m.message_status = 'COMMITTED'
            WHERE w.work_id = ? AND w.status = 'PROCESSING'
              AND w.lease_owner = ? AND w.version = ?
            """;

    private static final String COMPLETE = """
            UPDATE memory_extraction_work
            SET status = 'COMPLETED', completed_at = ?, lease_owner = NULL,
                lease_expires_at = NULL, last_error_code = NULL,
                version = version + 1, updated_at = ?
            WHERE work_id = ? AND status = 'PROCESSING'
              AND lease_owner = ? AND version = ?
            """;

    private static final String RETRY = """
            UPDATE memory_extraction_work
            SET status = ?, available_at = ?, lease_owner = NULL,
                lease_expires_at = NULL, last_error_code = ?,
                completed_at = ?, version = version + 1, updated_at = ?
            WHERE work_id = ? AND status = 'PROCESSING'
              AND lease_owner = ? AND version = ?
            """;

    private final JdbcOperations jdbc;
    private final TurnInputBindingJsonCodec inputCodec = new TurnInputBindingJsonCodec();

    public MySqlAutoMemoryExtractionWorkAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public void enqueue(TurnKey turn, String diagramId) {
        Objects.requireNonNull(turn, "turn");
        if (diagramId == null || diagramId.isBlank()) {
            throw new IllegalArgumentException("diagramId must not be blank");
        }
        int updated = jdbc.update(
                ENQUEUE,
                "memory_work_" + UUID.randomUUID(),
                turn.ownerKey(),
                turn.canonicalConversationId(),
                turn.turnId(),
                turn.canonicalConversationId(),
                turn.turnId(),
                diagramId,
                turn.ownerKey());
        if (updated < 0 || updated > 2) {
            throw new IllegalStateException("AUTO_MEMORY_WORK_ENQUEUE_INVALID");
        }
    }

    @Override
    @Transactional
    public Optional<AutoMemoryExtractionLease> claim(
            String workerId,
            Instant now,
            Duration leaseDuration
    ) {
        required(workerId, "workerId");
        Objects.requireNonNull(now, "now");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        List<WorkFence> candidates = jdbc.query(
                CLAIM_CANDIDATE,
                (resultSet, rowNumber) ->
                        new WorkFence(resultSet.getString("work_id"), resultSet.getLong("version")),
                Timestamp.from(now),
                Timestamp.from(now));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        WorkFence candidate = candidates.get(0);
        long claimedVersion = candidate.version() + 1;
        int claimed = jdbc.update(
                CLAIM,
                workerId,
                Timestamp.from(now.plus(leaseDuration)),
                Timestamp.from(now),
                candidate.workId(),
                candidate.version(),
                Timestamp.from(now),
                Timestamp.from(now));
        if (claimed != 1) {
            return Optional.empty();
        }
        List<AutoMemoryExtractionLease> leases = jdbc.query(
                LOAD,
                (resultSet, rowNumber) -> {
                    TurnDeclarations declarations =
                            inputCodec.decode(resultSet.getString("turn_input_binding_json"));
                    String explicitText =
                            declarations.memoryWrite() instanceof RememberDecisionDeclaration remember
                                    && remember.hasPinnedProposal()
                                    ? remember.canonicalText() : null;
                    return new AutoMemoryExtractionLease(
                            resultSet.getString("work_id"),
                            workerId,
                            resultSet.getLong("version"),
                            resultSet.getInt("attempt_count"),
                            new TurnKey(
                                    resultSet.getString("owner_key"),
                                    resultSet.getString("source_conversation_id"),
                                    resultSet.getString("source_turn_id")),
                            resultSet.getString("source_diagram_id"),
                            resultSet.getString("chartbook_id"),
                            resultSet.getString("user_content"),
                            explicitText);
                },
                candidate.workId(),
                workerId,
                claimedVersion);
        if (leases.size() != 1) {
            throw new IllegalStateException("AUTO_MEMORY_WORK_INPUT_UNAVAILABLE");
        }
        return Optional.of(leases.get(0));
    }

    @Override
    @Transactional
    public boolean complete(AutoMemoryExtractionLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        return jdbc.update(
                COMPLETE,
                Timestamp.from(now),
                Timestamp.from(now),
                lease.workId(),
                lease.workerId(),
                lease.version()) == 1;
    }

    @Override
    @Transactional
    public boolean retry(
            AutoMemoryExtractionLease lease,
            String errorCode,
            Instant availableAt,
            Instant now
    ) {
        Objects.requireNonNull(lease, "lease");
        required(errorCode, "errorCode");
        Objects.requireNonNull(availableAt, "availableAt");
        Objects.requireNonNull(now, "now");
        boolean exhausted = lease.attemptCount() >= MAX_ATTEMPTS;
        return jdbc.update(
                RETRY,
                exhausted ? "FAILED" : "PENDING",
                Timestamp.from(availableAt),
                bounded(errorCode, 64),
                exhausted ? Timestamp.from(now) : null,
                Timestamp.from(now),
                lease.workId(),
                lease.workerId(),
                lease.version()) == 1;
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String bounded(String value, int limit) {
        String normalized = value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private record WorkFence(String workId, long version) {
    }
}
