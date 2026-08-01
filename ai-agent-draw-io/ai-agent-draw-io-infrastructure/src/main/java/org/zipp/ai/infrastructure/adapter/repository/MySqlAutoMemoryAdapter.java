package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryActivationPolicy;
import org.zipp.ai.application.memory.AutoMemoryFence;
import org.zipp.ai.application.memory.AutoMemoryManagementOutcome;
import org.zipp.ai.application.memory.AutoMemoryManagementStorePort;
import org.zipp.ai.application.memory.AutoMemoryMaintenancePort;
import org.zipp.ai.application.memory.AutoMemoryObservationOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationStorePort;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.memory.SanitizedAutoMemoryObservation;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * MySQL authority for automatic Memory. One row is locked while evidence and lifecycle state are
 * consolidated, so concurrent post-turn retries cannot over-count or bypass a user-disabled item.
 */
@Repository
public class MySqlAutoMemoryAdapter
        implements AutoMemoryObservationStorePort, AutoMemoryQueryPort,
        AutoMemoryManagementStorePort, AutoMemoryMaintenancePort {

    private static final String VERIFY_CHARTBOOK = """
            SELECT COUNT(*)
            FROM chartbook
            WHERE id = ? AND owner_key = ? AND status = 'ACTIVE'
            """;

    private static final String INSERT_ITEM = """
            INSERT INTO memory_item (
                memory_id, owner_key, scope_type, scope_key, chartbook_id,
                memory_type, semantic_key, title, canonical_text, status,
                confidence, evidence_count, is_explicit, policy_version,
                source_conversation_id, source_turn_id, source_diagram_id,
                version, activated_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, 1, ?, ?, ?)
            ON DUPLICATE KEY UPDATE memory_id = memory_id
            """;

    private static final String SELECT_ITEM_FOR_UPDATE = """
            SELECT memory_id, owner_key, scope_type, scope_key, memory_type,
                   semantic_key, title, canonical_text, status, confidence,
                   evidence_count, is_explicit, version, created_at, updated_at
            FROM memory_item
            WHERE owner_key = ? AND scope_type = ? AND scope_key = ? AND semantic_key = ?
            FOR UPDATE
            """;

    private static final String INSERT_EVIDENCE = """
            INSERT IGNORE INTO memory_evidence (
                evidence_id, memory_id, owner_key,
                source_conversation_id, source_turn_id, source_diagram_id,
                observation_kind, observation_digest, observed_text, confidence,
                disposition, policy_version, observed_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String COUNT_SUPPORTING_TURNS = """
            SELECT COUNT(DISTINCT source_conversation_id, source_turn_id)
            FROM memory_evidence
            WHERE memory_id = ? AND disposition = 'SUPPORTING'
            """;

    private static final String COUNT_MATCHING_CONFLICT_TURNS = """
            SELECT COUNT(DISTINCT source_conversation_id, source_turn_id)
            FROM memory_evidence
            WHERE memory_id = ? AND disposition = 'CONFLICTING' AND observed_text = ?
            """;

    private static final String SUPERSEDE_SUPPORTING_EVIDENCE = """
            UPDATE memory_evidence
            SET disposition = 'SUPERSEDED'
            WHERE memory_id = ? AND disposition = 'SUPPORTING'
            """;

    private static final String SUPERSEDE_DISPLACED_EVIDENCE = """
            UPDATE memory_evidence
            SET disposition = 'SUPERSEDED'
            WHERE memory_id = ? AND (
                disposition = 'SUPPORTING'
                OR (disposition = 'CONFLICTING' AND observed_text <> ?)
            )
            """;

    private static final String PROMOTE_MATCHING_CONFLICT_EVIDENCE = """
            UPDATE memory_evidence
            SET disposition = 'SUPPORTING'
            WHERE memory_id = ? AND disposition = 'CONFLICTING' AND observed_text = ?
            """;

    private static final String UPDATE_REPLACED_ITEM = """
            UPDATE memory_item
            SET memory_type = ?, title = ?, canonical_text = ?, status = 'ACTIVE',
                confidence = ?, evidence_count = 1, is_explicit = 1,
                policy_version = ?, source_conversation_id = ?, source_turn_id = ?,
                source_diagram_id = ?, version = version + 1, activated_at = ?,
                updated_at = ?
            WHERE memory_id = ?
            """;

    private static final String UPDATE_PROMOTED_CHALLENGER = """
            UPDATE memory_item
            SET memory_type = ?, title = ?, canonical_text = ?, status = 'ACTIVE',
                confidence = ?, evidence_count = ?, is_explicit = 0,
                policy_version = ?, source_conversation_id = ?, source_turn_id = ?,
                source_diagram_id = ?, version = version + 1, activated_at = ?,
                updated_at = ?
            WHERE memory_id = ?
            """;

    private static final String UPDATE_SUPPORTED_ITEM = """
            UPDATE memory_item
            SET status = ?, confidence = GREATEST(confidence, ?),
                evidence_count = ?, is_explicit = (is_explicit OR ?),
                policy_version = ?, source_conversation_id = ?, source_turn_id = ?,
                source_diagram_id = ?, version = version + 1,
                activated_at = CASE
                    WHEN activated_at IS NULL AND ? = 'ACTIVE' THEN ?
                    ELSE activated_at
                END,
                updated_at = ?
            WHERE memory_id = ?
            """;

    private static final String SELECT_ACTIVE = """
            SELECT memory_id, owner_key, scope_type, scope_key, memory_type,
                   semantic_key, title, canonical_text, status, confidence,
                   evidence_count, is_explicit, version, created_at, updated_at
            FROM memory_item
            WHERE owner_key = ? AND scope_type = ? AND scope_key = ? AND status = 'ACTIVE'
            ORDER BY is_explicit DESC, confidence DESC, updated_at DESC, memory_id
            LIMIT ?
            """;

    private static final String SELECT_CONSOLIDATION_CANDIDATES = """
            SELECT memory_id, owner_key, scope_type, scope_key, memory_type,
                   semantic_key, title, canonical_text, status, confidence,
                   evidence_count, is_explicit, version, created_at, updated_at
            FROM memory_item
            WHERE owner_key = ? AND scope_type = ? AND scope_key = ?
              AND status <> 'DELETED'
            ORDER BY CASE status
                         WHEN 'OBSERVED' THEN 0
                         WHEN 'DISABLED' THEN 1
                         ELSE 2
                     END,
                     updated_at DESC, memory_id
            LIMIT ?
            """;

    private static final String SELECT_MANAGED = """
            SELECT memory_id, owner_key, scope_type, scope_key, memory_type,
                   semantic_key, title, canonical_text, status, confidence,
                   evidence_count, is_explicit, version, created_at, updated_at
            FROM memory_item
            WHERE owner_key = ? AND scope_type = ? AND scope_key = ?
              AND status <> 'DELETED'
              AND (? OR status <> 'OBSERVED')
              AND (? OR status <> 'DISABLED')
            ORDER BY status = 'ACTIVE' DESC, is_explicit DESC,
                     confidence DESC, updated_at DESC, memory_id
            LIMIT 200
            """;

    private static final String SELECT_MANAGED_FOR_UPDATE = """
            SELECT memory_id, owner_key, scope_type, scope_key, memory_type,
                   semantic_key, title, canonical_text, status, confidence,
                   evidence_count, is_explicit, version, created_at, updated_at
            FROM memory_item
            WHERE memory_id = ? AND owner_key = ? AND scope_type = ? AND scope_key = ?
            FOR UPDATE
            """;

    private static final String UPDATE_MANAGED_TEXT = """
            UPDATE memory_item
            SET canonical_text = ?,
                status = CASE WHEN status = 'DISABLED' THEN 'DISABLED' ELSE 'ACTIVE' END,
                confidence = 1.0000, evidence_count = 1, is_explicit = 1,
                policy_version = ?, source_conversation_id = 'memory-management',
                source_turn_id = ?, source_diagram_id = NULL,
                version = version + 1,
                activated_at = CASE
                    WHEN status <> 'DISABLED' AND activated_at IS NULL THEN ?
                    ELSE activated_at
                END,
                updated_at = ?
            WHERE memory_id = ? AND version = ?
            """;

    private static final String UPDATE_MANAGED_STATUS = """
            UPDATE memory_item
            SET status = ?, version = version + 1,
                activated_at = CASE
                    WHEN ? = 'ACTIVE' AND activated_at IS NULL THEN ?
                    ELSE activated_at
                END,
                updated_at = ?
            WHERE memory_id = ? AND version = ?
            """;

    private static final String DELETE_MANAGED = """
            DELETE FROM memory_item
            WHERE memory_id = ? AND owner_key = ? AND scope_type = ?
              AND scope_key = ? AND version = ?
            """;

    private static final String PURGE_STALE_OBSERVED = """
            DELETE FROM memory_item
            WHERE status = 'OBSERVED' AND is_explicit = 0 AND updated_at < ?
            ORDER BY updated_at, memory_id
            LIMIT ?
            """;

    private final JdbcOperations jdbc;

    public MySqlAutoMemoryAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public AutoMemoryObservationOutcome observe(
            SanitizedAutoMemoryObservation observation,
            AutoMemoryActivationPolicy activationPolicy
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(activationPolicy, "activationPolicy");
        if (!scopeExists(observation.scope())) {
            return new AutoMemoryObservationOutcome.Rejected("AUTO_MEMORY_SCOPE_NOT_FOUND");
        }

        AutoMemoryStatus initialStatus =
                activationPolicy.initialStatus(observation.explicit());
        String memoryId = "memory_" + UUID.randomUUID();
        jdbc.update(
                INSERT_ITEM,
                memoryId,
                observation.scope().ownerKey(),
                observation.scope().type().name(),
                observation.scope().scopeKey(),
                observation.scope().chartbookId(),
                observation.type().name(),
                observation.semanticKey(),
                observation.title(),
                observation.canonicalText(),
                initialStatus.name(),
                observation.confidence(),
                observation.explicit(),
                observation.policyVersion(),
                observation.sourceTurn().canonicalConversationId(),
                observation.sourceTurn().turnId(),
                observation.sourceDiagramId(),
                initialStatus == AutoMemoryStatus.ACTIVE
                        ? Timestamp.from(observation.observedAt()) : null,
                Timestamp.from(observation.observedAt()),
                Timestamp.from(observation.observedAt()));

        AutoMemory current = findForUpdate(observation);
        if (current == null) {
            throw new IllegalStateException("AUTO_MEMORY_ITEM_NOT_AVAILABLE");
        }
        // The generated id identifies insertion reliably even when the JDBC found-rows flag
        // changes ON DUPLICATE KEY UPDATE's affected-row count.
        if (current.memoryId().equals(memoryId)) {
            insertEvidence(current.memoryId(), observation, "SUPPORTING");
            return new AutoMemoryObservationOutcome.Applied(
                    current, true, current.status() == AutoMemoryStatus.ACTIVE);
        }
        if (current.status() == AutoMemoryStatus.DISABLED
                || current.status() == AutoMemoryStatus.DELETED) {
            return new AutoMemoryObservationOutcome.Suppressed(current);
        }

        if (!sameCanonicalText(current.canonicalText(), observation.canonicalText())) {
            if (!observation.explicit()) {
                int evidenceAdded = insertEvidence(
                        current.memoryId(), observation, "CONFLICTING");
                int conflictCount = matchingConflictTurnCount(
                        current.memoryId(), observation.canonicalText());
                // Explicit active content retains user authority; inference can only surface
                // conflicting evidence until the user edits or replaces it explicitly.
                if (!activationPolicy.shouldPromoteInferredChallenger(
                        current.explicit(), conflictCount)) {
                    return new AutoMemoryObservationOutcome.Conflict(current);
                }
                // Promote only one independently reinforced challenger generation. Older support
                // and alternative challengers cannot be reused later to cause a stale flip-flop.
                jdbc.update(
                        SUPERSEDE_DISPLACED_EVIDENCE,
                        current.memoryId(),
                        observation.canonicalText());
                int promoted = jdbc.update(
                        PROMOTE_MATCHING_CONFLICT_EVIDENCE,
                        current.memoryId(),
                        observation.canonicalText());
                if (promoted < conflictCount) {
                    throw new IllegalStateException("AUTO_MEMORY_CHALLENGER_EVIDENCE_MISSING");
                }
                int updated = jdbc.update(
                        UPDATE_PROMOTED_CHALLENGER,
                        observation.type().name(),
                        observation.title(),
                        observation.canonicalText(),
                        observation.confidence(),
                        conflictCount,
                        observation.policyVersion(),
                        observation.sourceTurn().canonicalConversationId(),
                        observation.sourceTurn().turnId(),
                        observation.sourceDiagramId(),
                        Timestamp.from(observation.observedAt()),
                        Timestamp.from(observation.observedAt()),
                        current.memoryId());
                if (updated != 1) {
                    throw new IllegalStateException("AUTO_MEMORY_CHALLENGER_NOT_APPLIED");
                }
                AutoMemory replaced = findForUpdate(observation);
                return new AutoMemoryObservationOutcome.Applied(
                        replaced,
                        evidenceAdded > 0,
                        current.status() != AutoMemoryStatus.ACTIVE);
            }
            // A later explicit user statement supersedes earlier supporting evidence for this key.
            jdbc.update(SUPERSEDE_SUPPORTING_EVIDENCE, current.memoryId());
            insertEvidence(current.memoryId(), observation, "SUPPORTING");
            jdbc.update(
                    UPDATE_REPLACED_ITEM,
                    observation.type().name(),
                    observation.title(),
                    observation.canonicalText(),
                    observation.confidence(),
                    observation.policyVersion(),
                    observation.sourceTurn().canonicalConversationId(),
                    observation.sourceTurn().turnId(),
                    observation.sourceDiagramId(),
                    Timestamp.from(observation.observedAt()),
                    Timestamp.from(observation.observedAt()),
                    current.memoryId());
            AutoMemory replaced = findForUpdate(observation);
            return new AutoMemoryObservationOutcome.Applied(
                    replaced, true, current.status() != AutoMemoryStatus.ACTIVE);
        }

        int evidenceAdded = insertEvidence(current.memoryId(), observation, "SUPPORTING");
        if (evidenceAdded == 0) {
            return new AutoMemoryObservationOutcome.Applied(current, false, false);
        }
        int evidenceCount = supportingTurnCount(current.memoryId());
        AutoMemoryStatus nextStatus = activationPolicy.afterSupportingEvidence(
                current.status(), observation.explicit(), evidenceCount);
        boolean activated = current.status() != AutoMemoryStatus.ACTIVE
                && nextStatus == AutoMemoryStatus.ACTIVE;
        jdbc.update(
                UPDATE_SUPPORTED_ITEM,
                nextStatus.name(),
                observation.confidence(),
                evidenceCount,
                observation.explicit(),
                observation.policyVersion(),
                observation.sourceTurn().canonicalConversationId(),
                observation.sourceTurn().turnId(),
                observation.sourceDiagramId(),
                nextStatus.name(),
                Timestamp.from(observation.observedAt()),
                Timestamp.from(observation.observedAt()),
                current.memoryId());
        return new AutoMemoryObservationOutcome.Applied(
                findForUpdate(observation), true, activated);
    }

    @Override
    public List<AutoMemory> recallActive(AutoMemoryScope scope, int limit) {
        Objects.requireNonNull(scope, "scope");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return List.copyOf(jdbc.query(
                SELECT_ACTIVE,
                this::mapMemory,
                scope.ownerKey(),
                scope.type().name(),
                scope.scopeKey(),
                limit));
    }

    @Override
    public List<AutoMemory> findConsolidationCandidates(
            AutoMemoryScope scope,
            int limit
    ) {
        Objects.requireNonNull(scope, "scope");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return List.copyOf(jdbc.query(
                SELECT_CONSOLIDATION_CANDIDATES,
                this::mapMemory,
                scope.ownerKey(),
                scope.type().name(),
                scope.scopeKey(),
                limit));
    }

    @Override
    public List<AutoMemory> list(
            AutoMemoryScope scope,
            boolean includeObserved,
            boolean includeDisabled
    ) {
        Objects.requireNonNull(scope, "scope");
        return List.copyOf(jdbc.query(
                SELECT_MANAGED,
                this::mapMemory,
                scope.ownerKey(),
                scope.type().name(),
                scope.scopeKey(),
                includeObserved,
                includeDisabled));
    }

    @Override
    public int purgeStaleObserved(Instant cutoffExclusive, int limit) {
        Objects.requireNonNull(cutoffExclusive, "cutoffExclusive");
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        // The status and explicit guards are repeated in the atomic DELETE so a concurrent
        // activation or user edit cannot be removed by a stale maintenance read.
        return jdbc.update(PURGE_STALE_OBSERVED, Timestamp.from(cutoffExclusive), limit);
    }

    @Override
    @Transactional
    public AutoMemoryManagementOutcome edit(
            AutoMemoryFence fence,
            String canonicalText,
            String policyVersion,
            Instant now
    ) {
        Objects.requireNonNull(fence, "fence");
        required(canonicalText, "canonicalText");
        required(policyVersion, "policyVersion");
        Objects.requireNonNull(now, "now");
        AutoMemory current = managedForUpdate(fence);
        AutoMemoryManagementOutcome unavailable = unavailable(current, fence);
        if (unavailable != null) {
            return unavailable;
        }
        String sourceTurnId = "edit-" + current.memoryId() + "-v" + (current.version() + 1);
        jdbc.update(SUPERSEDE_SUPPORTING_EVIDENCE, current.memoryId());
        insertEvidence(current.memoryId(), new SanitizedAutoMemoryObservation(
                current.scope(),
                current.type(),
                current.semanticKey(),
                current.title(),
                canonicalText,
                new TurnKey(current.scope().ownerKey(), "memory-management", sourceTurnId),
                null,
                org.zipp.ai.application.memory.MemoryObservationKind.USER_EDIT,
                1.0d,
                policyVersion,
                ModelInputBinding.digestOf(
                        current.memoryId(), canonicalText, String.valueOf(current.version() + 1)),
                now),
                "SUPPORTING");
        int updated = jdbc.update(
                UPDATE_MANAGED_TEXT,
                canonicalText,
                policyVersion,
                sourceTurnId,
                Timestamp.from(now),
                Timestamp.from(now),
                current.memoryId(),
                current.version());
        if (updated != 1) {
            throw new IllegalStateException("AUTO_MEMORY_MANAGEMENT_FENCE_NOT_APPLIED");
        }
        return new AutoMemoryManagementOutcome.Updated(managedForUpdate(new AutoMemoryFence(
                fence.scope(), fence.memoryId(), fence.expectedVersion() + 1)));
    }

    @Override
    @Transactional
    public AutoMemoryManagementOutcome disable(AutoMemoryFence fence, Instant now) {
        return changeStatus(fence, AutoMemoryStatus.DISABLED, now);
    }

    @Override
    @Transactional
    public AutoMemoryManagementOutcome activate(AutoMemoryFence fence, Instant now) {
        return changeStatus(fence, AutoMemoryStatus.ACTIVE, now);
    }

    @Override
    @Transactional
    public AutoMemoryManagementOutcome delete(AutoMemoryFence fence) {
        Objects.requireNonNull(fence, "fence");
        AutoMemory current = managedForUpdate(fence);
        AutoMemoryManagementOutcome unavailable = unavailable(current, fence);
        if (unavailable != null) {
            return unavailable;
        }
        int deleted = jdbc.update(
                DELETE_MANAGED,
                fence.memoryId(),
                fence.scope().ownerKey(),
                fence.scope().type().name(),
                fence.scope().scopeKey(),
                fence.expectedVersion());
        return deleted == 1
                ? new AutoMemoryManagementOutcome.Deleted(fence.memoryId())
                : new AutoMemoryManagementOutcome.Rejected("AUTO_MEMORY_VERSION_CONFLICT");
    }

    private boolean scopeExists(AutoMemoryScope scope) {
        if (scope.type() == MemoryScopeType.USER) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                VERIFY_CHARTBOOK,
                Integer.class,
                scope.scopeKey(),
                scope.ownerKey());
        return count != null && count == 1;
    }

    private AutoMemory findForUpdate(SanitizedAutoMemoryObservation observation) {
        List<AutoMemory> rows = jdbc.query(
                SELECT_ITEM_FOR_UPDATE,
                this::mapMemory,
                observation.scope().ownerKey(),
                observation.scope().type().name(),
                observation.scope().scopeKey(),
                observation.semanticKey());
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private AutoMemory managedForUpdate(AutoMemoryFence fence) {
        List<AutoMemory> rows = jdbc.query(
                SELECT_MANAGED_FOR_UPDATE,
                this::mapMemory,
                fence.memoryId(),
                fence.scope().ownerKey(),
                fence.scope().type().name(),
                fence.scope().scopeKey());
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private AutoMemoryManagementOutcome changeStatus(
            AutoMemoryFence fence,
            AutoMemoryStatus status,
            Instant now
    ) {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(now, "now");
        AutoMemory current = managedForUpdate(fence);
        AutoMemoryManagementOutcome unavailable = unavailable(current, fence);
        if (unavailable != null) {
            return unavailable;
        }
        if (current.status() == status) {
            return new AutoMemoryManagementOutcome.Updated(current);
        }
        int updated = jdbc.update(
                UPDATE_MANAGED_STATUS,
                status.name(),
                status.name(),
                Timestamp.from(now),
                Timestamp.from(now),
                current.memoryId(),
                current.version());
        if (updated != 1) {
            throw new IllegalStateException("AUTO_MEMORY_MANAGEMENT_FENCE_NOT_APPLIED");
        }
        return new AutoMemoryManagementOutcome.Updated(managedForUpdate(new AutoMemoryFence(
                fence.scope(), fence.memoryId(), fence.expectedVersion() + 1)));
    }

    private static AutoMemoryManagementOutcome unavailable(
            AutoMemory current,
            AutoMemoryFence fence
    ) {
        if (current == null) {
            return new AutoMemoryManagementOutcome.Gone("AUTO_MEMORY_NOT_FOUND");
        }
        if (current.version() != fence.expectedVersion()) {
            return new AutoMemoryManagementOutcome.Rejected("AUTO_MEMORY_VERSION_CONFLICT");
        }
        return null;
    }

    private int insertEvidence(
            String memoryId,
            SanitizedAutoMemoryObservation observation,
            String disposition
    ) {
        return jdbc.update(
                INSERT_EVIDENCE,
                "evidence_" + UUID.randomUUID(),
                memoryId,
                observation.scope().ownerKey(),
                observation.sourceTurn().canonicalConversationId(),
                observation.sourceTurn().turnId(),
                observation.sourceDiagramId(),
                observation.observationKind().name(),
                observation.observationDigest(),
                observation.canonicalText(),
                observation.confidence(),
                disposition,
                observation.policyVersion(),
                Timestamp.from(observation.observedAt()),
                Timestamp.from(observation.observedAt()));
    }

    private int supportingTurnCount(String memoryId) {
        Integer count = jdbc.queryForObject(COUNT_SUPPORTING_TURNS, Integer.class, memoryId);
        if (count == null || count < 1) {
            throw new IllegalStateException("AUTO_MEMORY_SUPPORTING_EVIDENCE_MISSING");
        }
        return count;
    }

    private int matchingConflictTurnCount(String memoryId, String canonicalText) {
        Integer count = jdbc.queryForObject(
                COUNT_MATCHING_CONFLICT_TURNS,
                Integer.class,
                memoryId,
                canonicalText);
        return count == null ? 0 : count;
    }

    private AutoMemory mapMemory(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AutoMemory(
                resultSet.getString("memory_id"),
                new AutoMemoryScope(
                        resultSet.getString("owner_key"),
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
                resultSet.getLong("version"),
                instant(resultSet.getTimestamp("created_at")),
                instant(resultSet.getTimestamp("updated_at")));
    }

    private static boolean sameCanonicalText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
