package org.zipp.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionLease;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorkPort;
import org.zipp.ai.application.memory.MemoryScopeType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable projection outbox; a desired revision fences concurrent Item/Evidence changes. */
@Repository
@ConditionalOnProperty(
        name = {"app.memory.auto-enabled", "app.memory.vector.projection-enabled"},
        havingValue = "true")
public class MySqlAutoMemoryVectorProjectionWorkAdapter
        implements AutoMemoryVectorProjectionWorkPort {
    private static final int MAX_ATTEMPTS = 8;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final String ENQUEUE = """
            INSERT INTO memory_vector_projection_work (
                memory_id, desired_revision, status, attempt_count, available_at,
                version, projected_vector_ids, created_at, updated_at
            ) VALUES (?, 1, 'PENDING', 0, UTC_TIMESTAMP(3),
                      1, JSON_ARRAY(), UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                desired_revision = desired_revision + 1,
                version = version + CASE WHEN status = 'PROCESSING' THEN 0 ELSE 1 END,
                available_at = CASE
                    WHEN status = 'PROCESSING' THEN available_at
                    ELSE UTC_TIMESTAMP(3)
                END,
                attempt_count = CASE WHEN status = 'FAILED' THEN 0 ELSE attempt_count END,
                completed_at = NULL,
                last_error_code = CASE
                    WHEN status = 'PROCESSING' THEN last_error_code
                    ELSE NULL
                END,
                status = CASE WHEN status = 'PROCESSING' THEN 'PROCESSING' ELSE 'PENDING' END,
                updated_at = UTC_TIMESTAMP(3)
            """;

    private static final String CLAIM_CANDIDATE = """
            SELECT memory_id, version
            FROM memory_vector_projection_work
            WHERE (status = 'PENDING' AND available_at <= ?)
               OR (status = 'PROCESSING' AND lease_expires_at <= ?)
            ORDER BY available_at, created_at, memory_id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    private static final String CLAIM = """
            UPDATE memory_vector_projection_work
            SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                lease_owner = ?, lease_expires_at = ?, version = version + 1,
                last_error_code = NULL, updated_at = ?
            WHERE memory_id = ? AND version = ?
              AND ((status = 'PENDING' AND available_at <= ?)
                OR (status = 'PROCESSING' AND lease_expires_at <= ?))
            """;

    private static final String LOAD = """
            SELECT w.memory_id AS work_memory_id, w.desired_revision, w.attempt_count,
                   w.version AS work_version, w.projected_vector_ids,
                   m.owner_key, m.scope_type, m.scope_key, m.memory_type,
                   m.semantic_key, m.title, m.canonical_text, m.status,
                   m.confidence, m.evidence_count, m.is_explicit,
                   m.version AS memory_version, m.created_at, m.updated_at
            FROM memory_vector_projection_work w
            LEFT JOIN memory_item m ON m.memory_id = w.memory_id
            WHERE w.memory_id = ? AND w.status = 'PROCESSING'
              AND w.lease_owner = ? AND w.version = ?
            """;

    private static final String SELECT_CHALLENGERS = """
            SELECT observed_text
            FROM memory_evidence
            WHERE memory_id = ? AND disposition = 'CONFLICTING'
            GROUP BY observed_text
            ORDER BY MIN(observed_at), observed_text
            """;

    private static final String COMPLETE = """
            UPDATE memory_vector_projection_work
            SET status = 'COMPLETED', projected_vector_ids = ?, completed_at = ?,
                lease_owner = NULL, lease_expires_at = NULL, last_error_code = NULL,
                version = version + 1, updated_at = ?
            WHERE memory_id = ? AND status = 'PROCESSING'
              AND lease_owner = ? AND version = ? AND desired_revision = ?
            """;

    private static final String REQUEUE_STALE = """
            UPDATE memory_vector_projection_work
            SET status = 'PENDING', available_at = ?, lease_owner = NULL,
                lease_expires_at = NULL, version = version + 1, updated_at = ?
            WHERE memory_id = ? AND status = 'PROCESSING'
              AND lease_owner = ? AND version = ?
            """;

    private static final String RETRY = """
            UPDATE memory_vector_projection_work
            SET status = ?, available_at = ?, lease_owner = NULL,
                lease_expires_at = NULL, last_error_code = ?, completed_at = ?,
                version = version + 1, updated_at = ?
            WHERE memory_id = ? AND status = 'PROCESSING'
              AND lease_owner = ? AND version = ?
            """;

    private final JdbcOperations jdbc;
    private final ObjectMapper objectMapper;

    public MySqlAutoMemoryVectorProjectionWorkAdapter(
            JdbcOperations jdbc,
            ObjectMapper objectMapper
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public void enqueue(String memoryId) {
        jdbc.update(ENQUEUE, required(memoryId, "memoryId"));
    }

    @Override
    @Transactional
    public Optional<AutoMemoryVectorProjectionLease> claim(
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
                (resultSet, rowNumber) -> new WorkFence(
                        resultSet.getString("memory_id"), resultSet.getLong("version")),
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
                candidate.memoryId(),
                candidate.version(),
                Timestamp.from(now),
                Timestamp.from(now));
        if (claimed != 1) {
            return Optional.empty();
        }
        List<ProjectionSnapshot> snapshots = jdbc.query(
                LOAD,
                (resultSet, rowNumber) -> snapshot(resultSet),
                candidate.memoryId(),
                workerId,
                claimedVersion);
        if (snapshots.size() != 1) {
            throw new IllegalStateException("AUTO_MEMORY_VECTOR_WORK_INPUT_UNAVAILABLE");
        }
        return Optional.of(lease(snapshots.get(0), workerId));
    }

    @Override
    @Transactional
    public boolean complete(AutoMemoryVectorProjectionLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        int completed = jdbc.update(
                COMPLETE,
                encode(lease.desiredVectorIds()),
                Timestamp.from(now),
                Timestamp.from(now),
                lease.memoryId(),
                lease.workerId(),
                lease.version(),
                lease.desiredRevision());
        if (completed == 1) {
            return true;
        }
        // An enqueue during provider I/O leaves the lease intact but advances desired_revision.
        jdbc.update(
                REQUEUE_STALE,
                Timestamp.from(now),
                Timestamp.from(now),
                lease.memoryId(),
                lease.workerId(),
                lease.version());
        return false;
    }

    @Override
    @Transactional
    public boolean retry(
            AutoMemoryVectorProjectionLease lease,
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
                lease.memoryId(),
                lease.workerId(),
                lease.version()) == 1;
    }

    private AutoMemoryVectorProjectionLease lease(
            ProjectionSnapshot snapshot,
            String workerId
    ) {
        String memoryId = snapshot.memoryId();
        long revision = snapshot.desiredRevision();
        List<AutoMemoryVectorDocument> documents = new ArrayList<>();
        if (snapshot.memory() != null) {
            AutoMemory memory = snapshot.memory();
            if (memory.status() != AutoMemoryStatus.DELETED) {
                documents.add(AutoMemoryVectorDocument.current(memory, revision));
                for (String challenger : jdbc.queryForList(
                        SELECT_CHALLENGERS, String.class, memoryId)) {
                    documents.add(AutoMemoryVectorDocument.challenger(
                            memoryId,
                            memory.scope(),
                            memory.title(),
                            challenger,
                            revision));
                }
            }
        }
        return new AutoMemoryVectorProjectionLease(
                memoryId,
                workerId,
                snapshot.workVersion(),
                revision,
                snapshot.attemptCount(),
                documents,
                decode(snapshot.projectedVectorIds()));
    }

    private ProjectionSnapshot snapshot(ResultSet resultSet) throws SQLException {
        String memoryId = resultSet.getString("work_memory_id");
        String ownerKey = resultSet.getString("owner_key");
        AutoMemory memory = ownerKey == null ? null : mapMemory(resultSet, memoryId, ownerKey);
        return new ProjectionSnapshot(
                memoryId,
                resultSet.getLong("desired_revision"),
                resultSet.getInt("attempt_count"),
                resultSet.getLong("work_version"),
                resultSet.getString("projected_vector_ids"),
                memory);
    }

    private AutoMemory mapMemory(ResultSet resultSet, String memoryId, String ownerKey)
            throws SQLException {
        return new AutoMemory(
                memoryId,
                new AutoMemoryScope(
                        ownerKey,
                        MemoryScopeType.valueOf(resultSet.getString("scope_type")),
                        resultSet.getString("scope_key")),
                AutoMemoryType.valueOf(resultSet.getString("memory_type")),
                resultSet.getString("semantic_key"),
                resultSet.getString("title"),
                resultSet.getString("canonical_text"),
                AutoMemoryStatus.valueOf(resultSet.getString("status")),
                resultSet.getDouble("confidence"),
                resultSet.getInt("evidence_count"),
                resultSet.getBoolean("is_explicit"),
                resultSet.getLong("memory_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Set<String> decode(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("AUTO_MEMORY_VECTOR_MANIFEST_INVALID", failure);
        }
    }

    private String encode(Set<String> vectorIds) {
        try {
            return objectMapper.writeValueAsString(vectorIds.stream().sorted().toList());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("AUTO_MEMORY_VECTOR_MANIFEST_ENCODING_FAILED", failure);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String bounded(String value, int limit) {
        String normalized = value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private record WorkFence(String memoryId, long version) {
    }

    private record ProjectionSnapshot(
            String memoryId,
            long desiredRevision,
            int attemptCount,
            long workVersion,
            String projectedVectorIds,
            AutoMemory memory
    ) {
    }
}
