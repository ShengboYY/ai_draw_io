package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.planning.SourcePlanIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable, turn-scoped hand-off for the Direct visual projection. */
@Repository
public class MySqlDirectPreparationStore {

    private static final String INSERT = """
            INSERT INTO turn_source_direct_preparation (
                owner_key, conversation_id, turn_id, prepared_ref, plan_fingerprint,
                source_snapshot_ref, observation_fingerprint, canvas_xml
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                plan_fingerprint = IF(plan_fingerprint = VALUES(plan_fingerprint),
                    VALUES(plan_fingerprint), plan_fingerprint),
                source_snapshot_ref = IF(plan_fingerprint = VALUES(plan_fingerprint),
                    VALUES(source_snapshot_ref), source_snapshot_ref),
                observation_fingerprint = IF(plan_fingerprint = VALUES(plan_fingerprint),
                    VALUES(observation_fingerprint), observation_fingerprint),
                canvas_xml = IF(plan_fingerprint = VALUES(plan_fingerprint),
                    VALUES(canvas_xml), canvas_xml)
            """;
    private static final String SELECT = """
            SELECT prepared_ref, plan_fingerprint, source_snapshot_ref,
                   observation_fingerprint, canvas_xml
            FROM turn_source_direct_preparation
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ? AND prepared_ref = ?
            """;

    private final JdbcOperations jdbc;

    public MySqlDirectPreparationStore(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Transactional
    public void save(FencedAttempt attempt, String preparedRef, SourcePlanIdentity plan,
                     String sourceSnapshotRef, String observationFingerprint, String canvasXml) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(plan, "plan");
        jdbc.update(INSERT, attempt.key().ownerKey(), attempt.key().canonicalConversationId(),
                attempt.key().turnId(), required(preparedRef), plan.planFingerprint(),
                required(sourceSnapshotRef), required(observationFingerprint), required(canvasXml));
    }

    public Optional<Prepared> find(FencedAttempt attempt, String preparedRef) {
        Objects.requireNonNull(attempt, "attempt");
        List<Prepared> rows = jdbc.query(SELECT, (rs, rowNum) -> new Prepared(
                        rs.getString("prepared_ref"),
                        rs.getString("plan_fingerprint"),
                        rs.getString("source_snapshot_ref"),
                        rs.getString("observation_fingerprint"),
                        rs.getString("canvas_xml")),
                attempt.key().ownerKey(), attempt.key().canonicalConversationId(),
                attempt.key().turnId(), required(preparedRef));
        return rows.stream().findFirst();
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value is required");
        return value.trim();
    }

    public record Prepared(
            String preparedRef,
            String planFingerprint,
            String sourceSnapshotRef,
            String observationFingerprint,
            String canvasXml
    ) {
    }
}
