package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.planning.ClarificationReplyResolutionPort;
import org.zipp.ai.application.turn.planning.DirectCandidateFact;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.application.turn.planning.SourceProbeBinding;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Resolves natural-language option proposals using clarification rows only. */
@Repository
public class MySqlClarificationReplyResolutionAdapter
        implements ClarificationReplyResolutionPort {

    private static final String SELECT = """
            SELECT c.option_set_digest, c.expires_at,
                   CURRENT_TIMESTAMP(3) AS database_now,
                   o.candidate_ref, o.candidate_origin,
                   o.observation_fingerprint, o.clarification_ref,
                   o.lineage_fingerprint, o.declaration_digest,
                   o.context_read_set_digest, o.input_binding_digest
            FROM turn_clarification c
            JOIN turn_clarification_option o
              ON o.owner_key = c.owner_key
             AND o.conversation_id = c.conversation_id
             AND o.turn_id = c.turn_id
             AND o.clarification_id = c.clarification_id
            WHERE c.owner_key = ? AND c.conversation_id = ?
              AND c.turn_id = ? AND c.clarification_id = ?
              AND o.option_id = ?
            """;

    private final JdbcOperations jdbc;

    public MySqlClarificationReplyResolutionAdapter(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Outcome resolve(
            TurnKey execution,
            ReplyToClarification reply,
            SelectionProposal proposal
    ) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(reply, "reply");
        Objects.requireNonNull(proposal, "proposal");
        try {
            List<Row> rows = jdbc.query(
                    SELECT,
                    (rs, rowNum) -> new Row(
                            rs.getString("option_set_digest"),
                            instant(rs.getTimestamp("expires_at")),
                            instant(rs.getTimestamp("database_now")),
                            rs.getString("candidate_ref"),
                            DirectCandidateOrigin.valueOf(
                                    rs.getString("candidate_origin")),
                            rs.getString("observation_fingerprint"),
                            rs.getString("clarification_ref"),
                            rs.getString("lineage_fingerprint"),
                            rs.getString("declaration_digest"),
                            rs.getString("context_read_set_digest"),
                            rs.getString("input_binding_digest")),
                    execution.ownerKey(),
                    execution.canonicalConversationId(),
                    execution.turnId(),
                    reply.clarificationId().value(),
                    proposal.optionId());
            if (rows.isEmpty()) {
                return new Stale("CLARIFICATION_OPTION_STALE");
            }
            Row row = rows.get(0);
            if (!row.optionSetDigest().equals(proposal.optionSetDigest())) {
                return new Stale("CLARIFICATION_SET_MISMATCH");
            }
            if (row.expiresAt() == null || row.databaseNow() == null
                    || !row.expiresAt().isAfter(row.databaseNow())) {
                return new Stale("CLARIFICATION_EXPIRED");
            }
            SourceProbeBinding binding = new SourceProbeBinding(
                    execution,
                    new PlanningLineageFingerprint(row.lineageFingerprint()),
                    row.declarationDigest(),
                    row.contextReadSetDigest(),
                    row.inputBindingDigest());
            return new Verified(new DirectCandidateFact(
                    binding,
                    row.candidateRef(),
                    row.origin(),
                    row.observationFingerprint(),
                    row.clarificationRef()));
        } catch (DataAccessException exception) {
            return new Unavailable(
                    "CLARIFICATION_AUTHORITY_UNAVAILABLE", Duration.ofSeconds(1));
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Row(
            String optionSetDigest,
            Instant expiresAt,
            Instant databaseNow,
            String candidateRef,
            DirectCandidateOrigin origin,
            String observationFingerprint,
            String clarificationRef,
            String lineageFingerprint,
            String declarationDigest,
            String contextReadSetDigest,
            String inputBindingDigest
    ) {
    }
}
