package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.memory.ConfirmedMemory;
import org.zipp.ai.application.memory.ConfirmedMemoryStatus;
import org.zipp.ai.application.memory.MemoryCandidateFence;
import org.zipp.ai.application.memory.MemoryCandidateProposal;
import org.zipp.ai.application.memory.MemoryCandidateStatus;
import org.zipp.ai.application.memory.MemoryCandidateStorePort;
import org.zipp.ai.application.memory.MemoryManagementPort;
import org.zipp.ai.application.memory.MemoryManagementOutcome;
import org.zipp.ai.application.memory.ConfirmedMemoryEditCommand;
import org.zipp.ai.application.memory.ConfirmedMemoryFence;
import org.zipp.ai.application.memory.MemoryPolicySanitizer;
import org.zipp.ai.application.memory.MemoryMaterializeCommand;
import org.zipp.ai.application.memory.MemoryMaterializeOutcome;
import org.zipp.ai.application.memory.MemoryProposalOutcome;
import org.zipp.ai.application.memory.SanitizedMemoryProposal;
import org.zipp.ai.application.turn.TurnKey;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Owner-fenced MySQL Memory store. Candidate payloads are cleared in the same transaction as
 * materialization, revocation, and expiry; the confirmed table is the only recall source.
 */
@Repository
public class MySqlConfirmedMemoryAdapter implements MemoryCandidateStorePort, MemoryManagementPort {
    private static final long RETAIN_SECONDS = 30L * 24L * 60L * 60L;
    private static final String ACTIVE_CHARTBOOK = """
            SELECT id FROM chartbook
            WHERE id = ? AND owner_key = ? AND status = 'ACTIVE'
            """;
    private static final String ACTIVE_DIAGRAM = """
            SELECT id FROM diagram
            WHERE id = ? AND user_id = ? AND chartbook_id = ? AND deleted = 0
            """;
    private static final String SUCCESSFUL_TERMINAL = """
            SELECT status FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
            FOR UPDATE
            """;

    private static final String INSERT_CANDIDATE = """
            INSERT INTO chartbook_memory_candidate (
                candidate_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                source_diagram_id, decision_key, applicability_stage, scope, canonical_text,
                policy_version, declaration_digest, status, version, expires_at, retain_until
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1,
                      DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
                      DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND))
            """;
    private static final String SELECT_CANDIDATE = """
            SELECT candidate_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                   source_diagram_id, decision_key, applicability_stage, scope, canonical_text,
                   policy_version, declaration_digest, status, version, expires_at, retain_until,
                   payload_deleted_at, materialized_memory_id
            FROM chartbook_memory_candidate
            WHERE candidate_id = ? AND owner_key = ? AND chartbook_id = ?
              AND source_conversation_id = ? AND source_turn_id = ? AND declaration_digest = ?
            """;
    private static final String SELECT_CANDIDATE_FOR_UPDATE = SELECT_CANDIDATE + " FOR UPDATE";
    private static final String PENDING_CANDIDATES = """
            SELECT candidate_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                   source_diagram_id, decision_key, applicability_stage, scope, canonical_text,
                   policy_version, declaration_digest, status, version, expires_at, retain_until,
                   payload_deleted_at, materialized_memory_id
            FROM chartbook_memory_candidate
            WHERE owner_key = ? AND chartbook_id = ? AND status = 'PENDING'
              AND expires_at > CURRENT_TIMESTAMP(3)
            ORDER BY expires_at ASC, candidate_id ASC
            """;
    private static final String MARK_EXPIRED = """
            UPDATE chartbook_memory_candidate
            SET status = 'EXPIRED', canonical_text = NULL,
                payload_deleted_at = CURRENT_TIMESTAMP(3), version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE candidate_id = ? AND status = 'PENDING'
              AND expires_at <= CURRENT_TIMESTAMP(3)
            """;
    private static final String INSERT_MEMORY = """
            INSERT INTO chartbook_memory (
                memory_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                source_diagram_id, decision_key, applicability_stage, scope, canonical_text,
                policy_version, declaration_digest, status, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1)
            """;
    private static final String SELECT_MEMORY = """
            SELECT memory_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                   decision_key, applicability_stage, scope, canonical_text, status, version
            FROM chartbook_memory
            WHERE memory_id = ? AND owner_key = ? AND chartbook_id = ?
            """;
    private static final String MARK_MATERIALIZED = """
            UPDATE chartbook_memory_candidate
            SET status = 'MATERIALIZED', canonical_text = NULL,
                payload_deleted_at = CURRENT_TIMESTAMP(3), materialized_memory_id = ?,
                version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE candidate_id = ? AND status = 'PENDING'
            """;
    private static final String MARK_REVOKED = """
            UPDATE chartbook_memory_candidate
            SET status = 'REVOKED', canonical_text = NULL,
                payload_deleted_at = CURRENT_TIMESTAMP(3), version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE candidate_id = ? AND status = 'PENDING'
            """;
    private static final String RECALL = """
            SELECT memory_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                   decision_key, applicability_stage, scope, canonical_text, status, version
            FROM chartbook_memory
            WHERE owner_key = ? AND chartbook_id = ? AND status = 'ACTIVE'
            ORDER BY decision_key ASC, memory_id ASC
            LIMIT ?
            """;
    private static final String MANAGED_MEMORY_BASE = """
            SELECT memory_id, owner_key, chartbook_id, source_conversation_id, source_turn_id,
                   decision_key, applicability_stage, scope, canonical_text, status, version
            FROM chartbook_memory
            """;
    private static final String MANAGED_MEMORY = MANAGED_MEMORY_BASE
            + " WHERE owner_key = ? AND chartbook_id = ? AND status <> 'DELETED'"
            + " ORDER BY decision_key ASC, memory_id ASC";
    private static final String SELECT_MANAGED_MEMORY = MANAGED_MEMORY_BASE
            + " WHERE owner_key = ? AND chartbook_id = ? AND memory_id = ? AND status <> 'DELETED'";
    private static final String UPDATE_TEXT = """
            UPDATE chartbook_memory
            SET canonical_text = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE memory_id = ? AND owner_key = ? AND chartbook_id = ?
              AND status <> 'DELETED' AND version = ?
            """;
    private static final String DISABLE = """
            UPDATE chartbook_memory
            SET status = 'DISABLED', version = version + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE memory_id = ? AND owner_key = ? AND chartbook_id = ?
              AND status = 'ACTIVE' AND version = ?
            """;
    private static final String DELETE_MEMORY = """
            UPDATE chartbook_memory
            SET status = 'DELETED', canonical_text = NULL, version = version + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE memory_id = ? AND owner_key = ? AND chartbook_id = ?
              AND status <> 'DELETED' AND version = ?
            """;

    private final JdbcOperations jdbc;

    public MySqlConfirmedMemoryAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public MemoryProposalOutcome propose(SanitizedMemoryProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        List<String> terminalStatuses = jdbc.query(SUCCESSFUL_TERMINAL,
                (resultSet, rowNum) -> resultSet.getString("status"),
                proposal.turn().ownerKey(), proposal.turn().canonicalConversationId(), proposal.turn().turnId());
        if (terminalStatuses.stream().noneMatch("COMPLETED"::equals)) {
            // A candidate is never durable before the successful terminal is durable.
            return new MemoryProposalOutcome.Rejected("MEMORY_TERMINAL_NOT_SUCCESSFUL");
        }
        if (jdbc.query(ACTIVE_CHARTBOOK, (resultSet, rowNum) -> resultSet.getString("id"),
                proposal.chartbookId(), proposal.turn().ownerKey()).isEmpty()) {
            return new MemoryProposalOutcome.Rejected("MEMORY_CHARTBOOK_NOT_OWNED");
        }
        if (jdbc.query(ACTIVE_DIAGRAM, (resultSet, rowNum) -> resultSet.getString("id"),
                proposal.diagramId(), proposal.turn().ownerKey(), proposal.chartbookId()).isEmpty()) {
            return new MemoryProposalOutcome.Rejected("MEMORY_DIAGRAM_NOT_OWNED");
        }
        try {
            jdbc.update(
                    INSERT_CANDIDATE,
                    proposal.candidateId(), proposal.turn().ownerKey(), proposal.chartbookId(),
                    proposal.turn().canonicalConversationId(), proposal.turn().turnId(),
                    proposal.diagramId(), proposal.decisionKey(), proposal.applicabilityStage(),
                    proposal.scope(), proposal.canonicalText(), proposal.policyVersion(),
                    proposal.declarationDigest(), proposal.ttl().toSeconds(), RETAIN_SECONDS);
        } catch (DataAccessException exception) {
            MemoryCandidateProposal existing = findCandidate(proposal);
            if (existing != null) {
                return new MemoryProposalOutcome.AlreadyExists(existing);
            }
            return new MemoryProposalOutcome.Rejected("MEMORY_CANDIDATE_ID_CONFLICT");
        }
        MemoryCandidateProposal created = findCandidate(proposal);
        if (created == null) {
            throw new IllegalStateException("MEMORY_CANDIDATE_NOT_READ_BACK");
        }
        return new MemoryProposalOutcome.Accepted(created);
    }

    @Override
    public List<MemoryCandidateProposal> listPending(String ownerKey, String chartbookId) {
        if (ownerKey == null || ownerKey.isBlank() || chartbookId == null || chartbookId.isBlank()) {
            return List.of();
        }
        return jdbc.query(PENDING_CANDIDATES, this::mapCandidate, ownerKey, chartbookId);
    }

    @Override
    @Transactional
    public MemoryMaterializeOutcome materialize(MemoryMaterializeCommand command) {
        Objects.requireNonNull(command, "command");
        MemoryCandidateFence fence = command.fence();
        MemoryCandidateProposal candidate = findCandidate(fence, true);
        if (candidate == null) {
            return new MemoryMaterializeOutcome.Gone("MEMORY_CANDIDATE_NOT_FOUND");
        }
        if (candidate.status() == MemoryCandidateStatus.MATERIALIZED) {
            ConfirmedMemory memory = findMemory(
                    candidate.materializedMemoryId(), fence.turn().ownerKey(), fence.chartbookId());
            return memory == null
                    ? new MemoryMaterializeOutcome.Gone("MEMORY_MATERIALIZATION_MISSING")
                    : new MemoryMaterializeOutcome.AlreadyMaterialized(memory);
        }
        if (candidate.status() != MemoryCandidateStatus.PENDING) {
            return new MemoryMaterializeOutcome.Gone(
                    "MEMORY_CANDIDATE_" + candidate.status().name());
        }
        if (jdbc.update(MARK_EXPIRED, candidate.candidateId()) == 1) {
            return new MemoryMaterializeOutcome.Gone("MEMORY_CANDIDATE_EXPIRED");
        }
        try {
            jdbc.update(
                    INSERT_MEMORY,
                    candidate.candidateId(), fence.turn().ownerKey(), fence.chartbookId(),
                    fence.turn().canonicalConversationId(), fence.turn().turnId(),
                    candidate.diagramId(), candidate.decisionKey(), candidate.applicabilityStage(),
                    candidate.scope(), candidate.canonicalText(), candidate.policyVersion(),
                    candidate.declarationDigest());
        } catch (DataAccessException exception) {
            return new MemoryMaterializeOutcome.Rejected("MEMORY_DECISION_KEY_CONFLICT");
        }
        int updated = jdbc.update(MARK_MATERIALIZED, candidate.candidateId(), candidate.candidateId());
        if (updated != 1) {
            throw new IllegalStateException("MEMORY_CANDIDATE_MATERIALIZATION_LOST");
        }
        ConfirmedMemory memory = findMemory(
                candidate.candidateId(), fence.turn().ownerKey(), fence.chartbookId());
        if (memory == null) {
            throw new IllegalStateException("MEMORY_NOT_READ_BACK");
        }
        return new MemoryMaterializeOutcome.Materialized(memory);
    }

    @Override
    @Transactional
    public MemoryMaterializeOutcome revoke(MemoryCandidateFence fence) {
        Objects.requireNonNull(fence, "fence");
        MemoryCandidateProposal candidate = findCandidate(fence, true);
        if (candidate == null) {
            return new MemoryMaterializeOutcome.Gone("MEMORY_CANDIDATE_NOT_FOUND");
        }
        if (candidate.status() != MemoryCandidateStatus.PENDING) {
            return new MemoryMaterializeOutcome.Gone(
                    "MEMORY_CANDIDATE_" + candidate.status().name());
        }
        if (jdbc.update(MARK_REVOKED, candidate.candidateId()) != 1) {
            throw new IllegalStateException("MEMORY_CANDIDATE_REVOKE_LOST");
        }
        return new MemoryMaterializeOutcome.Gone("MEMORY_CANDIDATE_REVOKED");
    }

    @Override
    @Transactional
    public MemoryMaterializeOutcome delete(MemoryCandidateFence fence) {
        // Delete is deliberately the same irreversible scrub transition as revoke in v1.
        return revoke(fence);
    }

    @Override
    public List<ConfirmedMemory> recall(String ownerKey, String chartbookId, int limit) {
        if (ownerKey == null || ownerKey.isBlank() || chartbookId == null || chartbookId.isBlank()) {
            return List.of();
        }
        int boundedLimit = Math.max(0, Math.min(limit, 8));
        if (boundedLimit == 0) {
            return List.of();
        }
        return jdbc.query(RECALL, this::mapMemory, ownerKey, chartbookId, boundedLimit);
    }

    @Override
    public List<ConfirmedMemory> list(String ownerKey, String chartbookId, boolean includeDisabled) {
        if (ownerKey == null || ownerKey.isBlank() || chartbookId == null || chartbookId.isBlank()) {
            return List.of();
        }
        String statusClause = includeDisabled ? "" : " AND status = 'ACTIVE'";
        return jdbc.query(MANAGED_MEMORY_BASE
                        + " WHERE owner_key = ? AND chartbook_id = ? AND status <> 'DELETED'"
                        + statusClause + " ORDER BY decision_key ASC, memory_id ASC",
                this::mapMemory, ownerKey, chartbookId);
    }

    @Override
    @Transactional
    public MemoryManagementOutcome edit(ConfirmedMemoryEditCommand command) {
        Objects.requireNonNull(command, "command");
        MemoryPolicySanitizer.TextSanitizationOutcome sanitized =
                new MemoryPolicySanitizer().sanitizeReplacement(command.canonicalText());
        if (sanitized instanceof MemoryPolicySanitizer.TextSanitizationOutcome.Rejected rejected) {
            return new MemoryManagementOutcome.Rejected(rejected.code());
        }
        ConfirmedMemoryFence fence = command.fence();
        int updated = jdbc.update(UPDATE_TEXT, ((MemoryPolicySanitizer.TextSanitizationOutcome.Accepted) sanitized).text(),
                fence.memoryId(), fence.ownerKey(), fence.chartbookId(), fence.expectedVersion());
        if (updated != 1) {
            return managementConflict(fence);
        }
        ConfirmedMemory memory = findManagedMemory(fence);
        return memory == null
                ? new MemoryManagementOutcome.Gone("MEMORY_NOT_FOUND")
                : new MemoryManagementOutcome.Updated(memory);
    }

    @Override
    @Transactional
    public MemoryManagementOutcome disable(ConfirmedMemoryFence fence) {
        Objects.requireNonNull(fence, "fence");
        int updated = jdbc.update(DISABLE, fence.memoryId(), fence.ownerKey(), fence.chartbookId(),
                fence.expectedVersion());
        if (updated != 1) {
            return managementConflict(fence);
        }
        ConfirmedMemory memory = findManagedMemory(fence);
        return memory == null
                ? new MemoryManagementOutcome.Gone("MEMORY_NOT_FOUND")
                : new MemoryManagementOutcome.Updated(memory);
    }

    @Override
    @Transactional
    public MemoryManagementOutcome delete(ConfirmedMemoryFence fence) {
        Objects.requireNonNull(fence, "fence");
        int updated = jdbc.update(DELETE_MEMORY, fence.memoryId(), fence.ownerKey(), fence.chartbookId(),
                fence.expectedVersion());
        return updated == 1
                ? new MemoryManagementOutcome.Gone("MEMORY_DELETED")
                : managementConflict(fence);
    }

    private MemoryManagementOutcome managementConflict(ConfirmedMemoryFence fence) {
        return jdbc.query(SELECT_MANAGED_MEMORY, this::mapMemory,
                        fence.ownerKey(), fence.chartbookId(), fence.memoryId()).isEmpty()
                ? new MemoryManagementOutcome.Gone("MEMORY_NOT_FOUND")
                : new MemoryManagementOutcome.Rejected("MEMORY_VERSION_CONFLICT");
    }

    private ConfirmedMemory findManagedMemory(ConfirmedMemoryFence fence) {
        List<ConfirmedMemory> rows = jdbc.query(SELECT_MANAGED_MEMORY, this::mapMemory,
                fence.ownerKey(), fence.chartbookId(), fence.memoryId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MemoryCandidateProposal findCandidate(SanitizedMemoryProposal proposal) {
        return findCandidate(new MemoryCandidateFence(
                proposal.turn(), proposal.chartbookId(), proposal.candidateId(),
                proposal.declarationDigest()), false);
    }

    private MemoryCandidateProposal findCandidate(MemoryCandidateFence fence, boolean forUpdate) {
        List<MemoryCandidateProposal> rows = jdbc.query(
                forUpdate ? SELECT_CANDIDATE_FOR_UPDATE : SELECT_CANDIDATE,
                this::mapCandidate,
                fence.candidateId(), fence.turn().ownerKey(), fence.chartbookId(),
                fence.turn().canonicalConversationId(), fence.turn().turnId(), fence.declarationDigest());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ConfirmedMemory findMemory(String memoryId, String ownerKey, String chartbookId) {
        if (memoryId == null) {
            return null;
        }
        List<ConfirmedMemory> rows = jdbc.query(
                SELECT_MEMORY, this::mapMemory, memoryId, ownerKey, chartbookId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MemoryCandidateProposal mapCandidate(ResultSet resultSet, int rowNum) throws SQLException {
        return new MemoryCandidateProposal(
                resultSet.getString("candidate_id"),
                new TurnKey(resultSet.getString("owner_key"),
                        resultSet.getString("source_conversation_id"), resultSet.getString("source_turn_id")),
                resultSet.getString("chartbook_id"), resultSet.getString("source_diagram_id"),
                resultSet.getString("decision_key"),
                resultSet.getString("applicability_stage"), resultSet.getString("scope"),
                resultSet.getString("canonical_text"), resultSet.getString("policy_version"),
                resultSet.getString("declaration_digest"),
                MemoryCandidateStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"), instant(resultSet.getTimestamp("expires_at")),
                instant(resultSet.getTimestamp("retain_until")),
                instant(resultSet.getTimestamp("payload_deleted_at")),
                resultSet.getString("materialized_memory_id"));
    }

    private ConfirmedMemory mapMemory(ResultSet resultSet, int rowNum) throws SQLException {
        return new ConfirmedMemory(
                resultSet.getString("memory_id"),
                new TurnKey(resultSet.getString("owner_key"),
                        resultSet.getString("source_conversation_id"), resultSet.getString("source_turn_id")),
                resultSet.getString("chartbook_id"), resultSet.getString("decision_key"),
                resultSet.getString("applicability_stage"), resultSet.getString("scope"),
                resultSet.getString("canonical_text"),
                ConfirmedMemoryStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
