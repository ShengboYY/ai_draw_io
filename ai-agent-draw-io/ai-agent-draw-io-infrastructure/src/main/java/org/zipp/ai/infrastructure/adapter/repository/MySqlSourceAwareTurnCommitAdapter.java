package org.zipp.ai.infrastructure.adapter.repository;

import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.DirectTurnCommit;
import org.zipp.ai.application.turn.DirectTurnCommitPort;
import org.zipp.ai.application.turn.DirectVisualProvenance;
import org.zipp.ai.application.turn.EvidenceAnswerTurnCommit;
import org.zipp.ai.application.turn.EvidenceAnswerTurnCommitPort;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.FencedCommitOutcome;
import org.zipp.ai.application.turn.GroundedTurnCommit;
import org.zipp.ai.application.turn.GroundedTurnCommitPort;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.TerminalOutcomeDecoder;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.ValidatedCitationManifest;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Three source-aware linearization points. Network work is complete before these short
 * transactions begin; every business write rolls back if terminal CAS cannot be applied.
 */
@Repository
public class MySqlSourceAwareTurnCommitAdapter
        implements DirectTurnCommitPort, GroundedTurnCommitPort,
        EvidenceAnswerTurnCommitPort {

    private static final String LOCK_EXECUTION = """
            SELECT e.current_attempt_id, e.attempt_epoch, e.lease_expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now, e.status, e.terminal_code,
                   e.terminal_payload_type, e.terminal_payload_ref,
                   e.terminal_payload_schema_version, e.terminal_payload_json,
                   e.updated_at, b.plan_fingerprint, b.source_snapshot_ref,
                   b.snapshot_binding_digest, b.execution_entry_id
            FROM turn_execution e
            LEFT JOIN turn_source_execution_binding b
              ON b.owner_key = e.owner_key
             AND b.conversation_id = e.conversation_id
             AND b.turn_id = e.turn_id
            WHERE e.owner_key = ? AND e.conversation_id = ? AND e.turn_id = ?
            FOR UPDATE
            """;
    private static final String LOCK_CONVERSATION = """
            SELECT version
            FROM conversation
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """;
    private static final String LOCK_CANVAS = """
            SELECT c.version, c.content_hash, c.summary, c.analysis_json
            FROM diagram d
            LEFT JOIN diagram_canvas_state c
              ON c.diagram_id = d.id AND c.user_id = d.user_id
            WHERE d.id = ? AND d.user_id = ? AND d.deleted = 0
            FOR UPDATE
            """;
    private static final String INSERT_CANVAS = """
            INSERT INTO diagram_canvas_state
                (diagram_id, user_id, current_xml, content_hash, version)
            VALUES (?, ?, ?, ?, 1)
            """;
    private static final String UPDATE_CANVAS = """
            UPDATE diagram_canvas_state
            SET current_xml = ?, content_hash = ?, version = version + 1,
                updated_at = UTC_TIMESTAMP(3)
            WHERE diagram_id = ? AND user_id = ? AND version = ?
            """;
    private static final String INSERT_CANVAS_VERSION = """
            INSERT INTO diagram_canvas_version (
                diagram_id, version, content_hash, canvas_xml,
                mutation_origin, created_by, run_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String ADVANCE_CONVERSATION = """
            UPDATE conversation
            SET version = version + 1
            WHERE id = ? AND owner_key = ? AND diagram_id = ? AND status = 'ACTIVE'
            """;
    private static final String INSERT_MESSAGE = """
            INSERT INTO diagram_conversation_message (
                diagram_id, conversation_id, user_id, session_id, turn_id,
                client_message_id, role, content, message_sequence, message_status
            ) VALUES (?, ?, ?, NULL, ?, ?, 'agent', ?, ?, 'COMMITTED')
            """;
    private static final String INSERT_VISUAL_PROVENANCE = """
            INSERT INTO diagram_visual_provenance (
                diagram_id, canvas_version, provenance_ref, source_identity_ref,
                candidate_origin, observation_fingerprint, source_snapshot_ref,
                plan_fingerprint, execution_entry_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPSERT_DIRECT_PIN = """
            INSERT INTO direct_source_usage_pin (
                owner_key, source_identity_ref, source_snapshot_ref,
                snapshot_binding_digest, state, first_used_at, latest_used_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                state = 'ACTIVE', latest_used_at = UTC_TIMESTAMP(3)
            """;
    private static final String INSERT_CITATION = """
            INSERT INTO source_citation (
                id, owner_key, target_type, diagram_id, canvas_version,
                cell_id, message_id, claim_key, support_type, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EVIDENCE', 'VERIFIED')
            """;
    private static final String INSERT_CITATION_EVIDENCE = """
            INSERT INTO citation_evidence (
                citation_id, evidence_id, version_id, revision_id,
                citation_key, use_role
            ) VALUES (?, ?, ?, ?, ?, 'RETRIEVAL')
            """;
    private static final String UPSERT_EVIDENCE_PIN = """
            INSERT INTO diagram_source_pin (
                diagram_id, material_id, version_id, processing_revision_id,
                state, first_used_at, latest_citation_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                state = 'ACTIVE', latest_citation_at = UTC_TIMESTAMP(3)
            """;
    private static final String COMMIT_EXECUTION = """
            UPDATE turn_execution
            SET response_message_id = ?, canvas_version_before = ?,
                canvas_version_after = ?, status = 'COMPLETED',
                terminal_code = 'COMPLETED', terminal_payload_type = ?,
                terminal_payload_schema_version = 1, terminal_payload_ref = ?,
                terminal_payload_json = ?, completed_at = UTC_TIMESTAMP(3)
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'RUNNING' AND current_attempt_id = ? AND attempt_epoch = ?
              AND lease_expires_at > CURRENT_TIMESTAMP(3)
            """;

    private final JdbcOperations jdbc;
    private final MySqlCompletedTurnMemoryProposalWriter memoryProposals;
    private final CanvasXmlContentHasher canvasHasher = new CanvasXmlContentHasher();
    private final TerminalOutcomeDecoder terminalDecoder = new TerminalOutcomeDecoder();

    public MySqlSourceAwareTurnCommitAdapter(JdbcOperations jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public MySqlSourceAwareTurnCommitAdapter(
            JdbcOperations jdbc,
            MySqlCompletedTurnMemoryProposalWriter memoryProposals
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.memoryProposals = memoryProposals;
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(DirectTurnCommit command) {
        Objects.requireNonNull(command, "command");
        Prepared prepared = prepare(
                command.attempt(),
                command.sourceBinding(),
                command.diagramId());
        if (prepared.outcome() != null) {
            return prepared.outcome();
        }
        CanvasWrite canvas;
        try {
            canvas = writeCanvas(
                    command.attempt(),
                    command.diagramId(),
                    command.expectedCanvasVersion(),
                    command.expectedCanvasContextDigest(),
                    command.canvasXml(),
                    "DIRECT");
        } catch (CommitRejectedException rejected) {
            return new FencedCommitOutcome.Rejected(rejected.getMessage());
        }
        long messageId = writeAssistant(
                command.attempt(),
                command.diagramId(),
                prepared.conversationVersion(),
                command.assistantMessage());
        writeDirect(
                command.attempt().key().ownerKey(),
                command.diagramId(),
                canvas.versionAfter(),
                command.sourceBinding(),
                command.provenance());
        return complete(
                command.attempt(),
                command.sourceBinding(),
                command.diagramId(),
                "direct",
                command.payloadRef(),
                messageId,
                canvas.versionBefore(),
                canvas.versionAfter());
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(GroundedTurnCommit command) {
        Objects.requireNonNull(command, "command");
        Prepared prepared = prepare(
                command.attempt(),
                command.sourceBinding(),
                command.diagramId());
        if (prepared.outcome() != null) {
            return prepared.outcome();
        }
        CanvasWrite canvas;
        try {
            canvas = writeCanvas(
                    command.attempt(),
                    command.diagramId(),
                    command.expectedCanvasVersion(),
                    command.expectedCanvasContextDigest(),
                    command.canvasXml(),
                    "GROUNDED");
        } catch (CommitRejectedException rejected) {
            return new FencedCommitOutcome.Rejected(rejected.getMessage());
        }
        long messageId = writeAssistant(
                command.attempt(),
                command.diagramId(),
                prepared.conversationVersion(),
                command.assistantMessage());
        writeCitations(
                command.attempt().key().ownerKey(),
                command.diagramId(),
                canvas.versionAfter(),
                null,
                command.citations(),
                "DIAGRAM_CELL");
        command.directProvenance().ifPresent(provenance -> writeDirect(
                command.attempt().key().ownerKey(),
                command.diagramId(),
                canvas.versionAfter(),
                command.sourceBinding(),
                provenance));
        return complete(
                command.attempt(),
                command.sourceBinding(),
                command.diagramId(),
                "grounded",
                command.payloadRef(),
                messageId,
                canvas.versionBefore(),
                canvas.versionAfter());
    }

    @Override
    @Transactional
    public FencedCommitOutcome commit(EvidenceAnswerTurnCommit command) {
        Objects.requireNonNull(command, "command");
        Prepared prepared = prepare(
                command.attempt(),
                command.sourceBinding(),
                command.diagramId());
        if (prepared.outcome() != null) {
            return prepared.outcome();
        }
        try {
            validateAnswerCanvas(command);
        } catch (CommitRejectedException rejected) {
            return new FencedCommitOutcome.Rejected(rejected.getMessage());
        }
        long messageId = writeAssistant(
                command.attempt(),
                command.diagramId(),
                prepared.conversationVersion(),
                command.assistantMessage());
        writeCitations(
                command.attempt().key().ownerKey(),
                command.diagramId(),
                command.expectedTargetCanvasVersion() == 0
                        ? null : command.expectedTargetCanvasVersion(),
                String.valueOf(messageId),
                command.citations(),
                "ANSWER_CLAIM");
        return complete(
                command.attempt(),
                command.sourceBinding(),
                command.diagramId(),
                "evidence_answer",
                command.payloadRef(),
                messageId,
                command.expectedTargetCanvasVersion(),
                command.expectedTargetCanvasVersion());
    }

    private Prepared prepare(
            FencedAttempt attempt,
            SourceCommitBinding binding,
            String diagramId
    ) {
        ExecutionRow execution = lockExecution(attempt.key());
        if (execution == null) {
            return Prepared.blocked(
                    new FencedCommitOutcome.Rejected("TURN_EXECUTION_NOT_FOUND"));
        }
        if (execution.status().isTerminal()) {
            TerminalOutcomeDecoder.DecodeResult decoded = execution.decode(terminalDecoder);
            if (decoded instanceof TerminalOutcomeDecoder.DecodeResult.Decoded ready) {
                return Prepared.blocked(
                        new FencedCommitOutcome.AlreadyTerminal(ready.outcome()));
            }
            return Prepared.blocked(new FencedCommitOutcome.TerminalUnavailable(
                    execution.view(attempt.key()),
                    ((TerminalOutcomeDecoder.DecodeResult.Unavailable) decoded).code()));
        }
        if (!execution.active(attempt)) {
            return Prepared.blocked(
                    new FencedCommitOutcome.FenceLost(execution.view(attempt.key())));
        }
        if (!execution.matches(binding)) {
            return Prepared.blocked(
                    new FencedCommitOutcome.Rejected("SOURCE_COMMIT_BINDING_MISMATCH"));
        }
        Long conversationVersion = lockConversation(attempt.key(), diagramId);
        if (conversationVersion == null) {
            return Prepared.blocked(
                    new FencedCommitOutcome.Rejected("CONVERSATION_NOT_ACTIVE"));
        }
        return new Prepared(conversationVersion, null);
    }

    private CanvasWrite writeCanvas(
            FencedAttempt attempt,
            String diagramId,
            long expectedVersion,
            String expectedDigest,
            String xml,
            String mutationOrigin
    ) {
        CanvasRow canvas = lockCanvas(attempt.key().ownerKey(), diagramId);
        if (canvas == null) {
            throw new CommitRejectedException("DIAGRAM_NOT_ACTIVE");
        }
        String contentHash = canvasHasher.hash(xml);
        long versionAfter;
        if (expectedVersion == 0) {
            if (canvas.version() != null) {
                throw new CommitRejectedException("CANVAS_VERSION_CONFLICT");
            }
            requireOne(jdbc.update(
                    INSERT_CANVAS,
                    diagramId,
                    attempt.key().ownerKey(),
                    xml,
                    contentHash), "CANVAS_VERSION_CONFLICT");
            versionAfter = 1;
        } else {
            if (canvas.version() == null || canvas.version() != expectedVersion) {
                throw new CommitRejectedException("CANVAS_VERSION_CONFLICT");
            }
            if (!canvasDigest(diagramId, canvas).equals(expectedDigest)) {
                throw new CommitRejectedException("CANVAS_CONTEXT_CONFLICT");
            }
            requireOne(jdbc.update(
                    UPDATE_CANVAS,
                    xml,
                    contentHash,
                    diagramId,
                    attempt.key().ownerKey(),
                    expectedVersion), "CANVAS_VERSION_CONFLICT");
            versionAfter = expectedVersion + 1;
        }
        requireOne(jdbc.update(
                INSERT_CANVAS_VERSION,
                diagramId,
                versionAfter,
                contentHash,
                xml,
                mutationOrigin,
                attempt.key().ownerKey(),
                attempt.attemptId()), "CANVAS_VERSION_NOT_INSERTED");
        return new CanvasWrite(expectedVersion, versionAfter);
    }

    private void validateAnswerCanvas(EvidenceAnswerTurnCommit command) {
        if (command.expectedTargetCanvasVersion() == 0) {
            return;
        }
        CanvasRow canvas = lockCanvas(
                command.attempt().key().ownerKey(),
                command.diagramId());
        if (canvas == null || canvas.version() == null
                || canvas.version() != command.expectedTargetCanvasVersion()) {
            throw new CommitRejectedException("ANSWER_CANVAS_VERSION_CHANGED");
        }
        if (!canvasDigest(command.diagramId(), canvas)
                .equals(command.expectedTargetCanvasContextDigest())) {
            throw new CommitRejectedException("ANSWER_CANVAS_CONTEXT_CHANGED");
        }
    }

    private long writeAssistant(
            FencedAttempt attempt,
            String diagramId,
            long conversationVersion,
            String message
    ) {
        TurnKey key = attempt.key();
        requireOne(jdbc.update(
                ADVANCE_CONVERSATION,
                key.canonicalConversationId(),
                key.ownerKey(),
                diagramId), "CONVERSATION_VERSION_NOT_ADVANCED");
        long sequence = conversationVersion + 1;
        requireOne(jdbc.update(
                INSERT_MESSAGE,
                diagramId,
                key.canonicalConversationId(),
                key.ownerKey(),
                key.turnId(),
                assistantMessageId(key),
                message,
                sequence), "SOURCE_RESPONSE_MESSAGE_NOT_CREATED");
        Long messageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (messageId == null) {
            throw new IllegalStateException("SOURCE_RESPONSE_MESSAGE_ID_NOT_AVAILABLE");
        }
        return messageId;
    }

    private void writeDirect(
            String ownerKey,
            String diagramId,
            long canvasVersion,
            SourceCommitBinding binding,
            DirectVisualProvenance provenance
    ) {
        requireOne(jdbc.update(
                INSERT_VISUAL_PROVENANCE,
                diagramId,
                canvasVersion,
                provenance.provenanceRef(),
                provenance.sourceIdentityRef(),
                provenance.origin().name(),
                provenance.observationFingerprint(),
                binding.sourceSnapshotRef(),
                binding.planIdentity().planFingerprint(),
                binding.executionEntryId()), "DIRECT_PROVENANCE_NOT_WRITTEN");
        requireOne(jdbc.update(
                UPSERT_DIRECT_PIN,
                ownerKey,
                provenance.sourceIdentityRef(),
                binding.sourceSnapshotRef(),
                binding.snapshotBindingDigest()), "DIRECT_SOURCE_PIN_NOT_WRITTEN");
    }

    private void writeCitations(
            String ownerKey,
            String diagramId,
            Long canvasVersion,
            String messageId,
            ValidatedCitationManifest manifest,
            String targetType
    ) {
        for (ValidatedCitationManifest.Citation citation : manifest.citations()) {
            requireOne(jdbc.update(
                    INSERT_CITATION,
                    citation.citationId(),
                    ownerKey,
                    targetType,
                    diagramId,
                    canvasVersion,
                    "DIAGRAM_CELL".equals(targetType) ? citation.targetKey() : null,
                    messageId,
                    citation.targetKey()), "SOURCE_CITATION_NOT_WRITTEN");
            for (ValidatedCitationManifest.EvidenceLink link : citation.links()) {
                requireOne(jdbc.update(
                        INSERT_CITATION_EVIDENCE,
                        citation.citationId(),
                        link.evidenceId(),
                        link.versionId(),
                        link.revisionId(),
                        link.citationKey()), "CITATION_EVIDENCE_NOT_WRITTEN");
                requireOne(jdbc.update(
                        UPSERT_EVIDENCE_PIN,
                        diagramId,
                        link.materialId(),
                        link.versionId(),
                        link.revisionId()), "EVIDENCE_PIN_NOT_WRITTEN");
            }
        }
    }

    private FencedCommitOutcome complete(
            FencedAttempt attempt,
            SourceCommitBinding binding,
            String diagramId,
            String payloadType,
            String payloadRef,
            long responseMessageId,
            long canvasVersionBefore,
            long canvasVersionAfter
    ) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of(
                "responseMessageId", responseMessageId,
                "planFingerprint", binding.planIdentity().planFingerprint(),
                "sourceSnapshotRef", binding.sourceSnapshotRef(),
                "snapshotBindingDigest", binding.snapshotBindingDigest(),
                "executionEntryId", binding.executionEntryId(),
                "payloadRef", payloadRef));
        if (!"evidence_answer".equals(payloadType)) {
            // Text answers do not mutate a canvas and must not cause the bridge to return one.
            payload.put("canvasVersionBefore", canvasVersionBefore);
            payload.put("canvasVersionAfter", canvasVersionAfter);
        }
        String payloadJson = JSON.toJSONString(payload);
        TurnKey key = attempt.key();
        requireOne(jdbc.update(
                COMMIT_EXECUTION,
                responseMessageId,
                canvasVersionBefore,
                canvasVersionAfter,
                payloadType,
                payloadRef,
                payloadJson,
                key.ownerKey(),
                key.canonicalConversationId(),
                key.turnId(),
                attempt.attemptId(),
                attempt.attemptEpoch()), "SOURCE_COMMIT_FENCE_NOT_APPLIED");
        if (memoryProposals != null) {
            memoryProposals.write(attempt, diagramId);
        }
        return new FencedCommitOutcome.Committed(new PersistedTurnOutcome(
                TurnStatus.COMPLETED,
                "COMPLETED",
                payloadType,
                payloadRef,
                payloadJson));
    }

    private ExecutionRow lockExecution(TurnKey key) {
        List<ExecutionRow> rows = jdbc.query(
                LOCK_EXECUTION,
                (rs, rowNum) -> new ExecutionRow(
                        rs.getString("current_attempt_id"),
                        rs.getLong("attempt_epoch"),
                        instant(rs.getTimestamp("lease_expires_at")),
                        instant(rs.getTimestamp("database_now")),
                        TurnStatus.valueOf(rs.getString("status")),
                        rs.getString("terminal_code"),
                        rs.getString("terminal_payload_type"),
                        rs.getString("terminal_payload_ref"),
                        rs.getObject("terminal_payload_schema_version", Integer.class),
                        rs.getString("terminal_payload_json"),
                        instant(rs.getTimestamp("updated_at")),
                        rs.getString("plan_fingerprint"),
                        rs.getString("source_snapshot_ref"),
                        rs.getString("snapshot_binding_digest"),
                        rs.getString("execution_entry_id")),
                key.ownerKey(), key.canonicalConversationId(), key.turnId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long lockConversation(TurnKey key, String diagramId) {
        List<Long> rows = jdbc.query(
                LOCK_CONVERSATION,
                (rs, rowNum) -> rs.getLong("version"),
                key.canonicalConversationId(), key.ownerKey(), diagramId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CanvasRow lockCanvas(String ownerKey, String diagramId) {
        List<CanvasRow> rows = jdbc.query(
                LOCK_CANVAS,
                (rs, rowNum) -> new CanvasRow(
                        rs.getObject("version", Long.class),
                        rs.getString("content_hash"),
                        rs.getString("summary"),
                        rs.getString("analysis_json")),
                diagramId, ownerKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String canvasDigest(String diagramId, CanvasRow canvas) {
        return sha256(String.join(
                "\n",
                diagramId,
                String.valueOf(canvas.version()),
                Objects.toString(canvas.contentHash(), ""),
                Objects.toString(canvas.summary(), ""),
                Objects.toString(canvas.analysisJson(), "")));
    }

    private String assistantMessageId(TurnKey key) {
        return "turn-v2-source-agent-" + sha256(
                key.ownerKey() + "|" + key.canonicalConversationId() + "|" + key.turnId())
                .substring(0, 24);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private void requireOne(int changed, String code) {
        if (changed != 1) {
            throw new IllegalStateException(code);
        }
    }

    private record Prepared(long conversationVersion, FencedCommitOutcome outcome) {
        static Prepared blocked(FencedCommitOutcome outcome) {
            return new Prepared(0, outcome);
        }
    }

    private record CanvasRow(
            Long version,
            String contentHash,
            String summary,
            String analysisJson
    ) {
    }

    private record CanvasWrite(long versionBefore, long versionAfter) {
    }

    private record ExecutionRow(
            String attemptId,
            long attemptEpoch,
            Instant leaseExpiresAt,
            Instant databaseNow,
            TurnStatus status,
            String terminalCode,
            String payloadType,
            String payloadRef,
            Integer payloadSchema,
            String payloadJson,
            Instant updatedAt,
            String planFingerprint,
            String sourceSnapshotRef,
            String snapshotBindingDigest,
            String executionEntryId
    ) {
        boolean active(FencedAttempt attempt) {
            return status == TurnStatus.RUNNING
                    && Objects.equals(attemptId, attempt.attemptId())
                    && attemptEpoch == attempt.attemptEpoch()
                    && leaseExpiresAt != null
                    && databaseNow != null
                    && leaseExpiresAt.isAfter(databaseNow);
        }

        boolean matches(SourceCommitBinding binding) {
            return Objects.equals(
                    planFingerprint,
                    binding.planIdentity().planFingerprint())
                    && Objects.equals(sourceSnapshotRef, binding.sourceSnapshotRef())
                    && Objects.equals(
                    snapshotBindingDigest,
                    binding.snapshotBindingDigest())
                    && Objects.equals(executionEntryId, binding.executionEntryId());
        }

        TurnStatusView view(TurnKey key) {
            return new TurnStatusView(
                    key, status, attemptId, attemptEpoch, terminalCode, payloadRef, updatedAt);
        }

        TerminalOutcomeDecoder.DecodeResult decode(TerminalOutcomeDecoder decoder) {
            return decoder.decode(
                    status,
                    terminalCode,
                    payloadSchema,
                    payloadType,
                    payloadRef,
                    payloadJson);
        }
    }

    private static final class CommitRejectedException extends RuntimeException {
        private CommitRejectedException(String code) {
            super(code);
        }
    }
}
