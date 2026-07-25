package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationStatus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canonical conversation lookup; legacy aliases are never synthesized from caller input. */
@Repository
public class MySqlConversationCatalogAdapter implements ConversationCatalogPort {

    private static final String SELECT_ACTIVE_DEFAULT = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation c
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE c.owner_key = ? AND c.diagram_id = ? AND c.status = 'ACTIVE' AND d.deleted = 0
            ORDER BY c.updated_at ASC, c.id ASC
            LIMIT 1
            """;
    private static final String SELECT_ACTIVE_BINDING = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation c
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE c.id = ? AND c.owner_key = ? AND c.diagram_id = ?
              AND c.status = 'ACTIVE' AND d.deleted = 0
            """;
    private static final String SELECT_ACTIVE_ALIAS = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation_legacy_alias a
            INNER JOIN conversation c ON c.id = a.conversation_id
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE a.owner_key = ? AND a.legacy_session_id = ? AND c.diagram_id = ?
              AND c.status = 'ACTIVE' AND d.deleted = 0
            """;
    private static final String INSERT_CONVERSATION = """
            INSERT INTO conversation (id, owner_key, diagram_id, status, version)
            VALUES (?, ?, ?, 'ACTIVE', 0)
            """;

    private final JdbcTemplate jdbc;

    public MySqlConversationCatalogAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
        Objects.requireNonNull(actor, "actor");
        requireText(diagramId, "diagramId");
        ConversationRef existing = findOne(SELECT_ACTIVE_DEFAULT, actor.ownerKey(), diagramId);
        if (existing != null) {
            return existing;
        }
        String id = "conv_" + UUID.randomUUID();
        jdbc.update(INSERT_CONVERSATION, id, actor.ownerKey(), diagramId);
        ConversationRef created = findOne(SELECT_ACTIVE_BINDING, id, actor.ownerKey(), diagramId);
        if (created == null) {
            throw new IllegalStateException("CONVERSATION_CREATE_NOT_VISIBLE");
        }
        return created;
    }

    @Override
    public ConversationRef requireActiveBinding(
            AuthenticatedActor actor,
            String conversationId,
            String diagramId
    ) {
        Objects.requireNonNull(actor, "actor");
        requireText(conversationId, "conversationId");
        requireText(diagramId, "diagramId");
        ConversationRef ref = findOne(SELECT_ACTIVE_BINDING, conversationId, actor.ownerKey(), diagramId);
        if (ref == null) {
            throw new IllegalStateException("CONVERSATION_NOT_ACTIVE");
        }
        return ref;
    }

    @Override
    public ConversationRef resolveLegacyAlias(
            AuthenticatedActor actor,
            String legacySessionId,
            String diagramId
    ) {
        Objects.requireNonNull(actor, "actor");
        requireText(legacySessionId, "legacySessionId");
        requireText(diagramId, "diagramId");
        ConversationRef ref = findOne(SELECT_ACTIVE_ALIAS, actor.ownerKey(), legacySessionId, diagramId);
        if (ref == null) {
            throw new IllegalStateException("LEGACY_CONVERSATION_ALIAS_UNRESOLVED");
        }
        return ref;
    }

    private ConversationRef findOne(String sql, Object... args) {
        List<ConversationRef> rows = jdbc.query(sql, (rs, rowNum) -> new ConversationRef(
                rs.getString("id"),
                rs.getString("owner_key"),
                rs.getString("diagram_id"),
                ConversationStatus.valueOf(rs.getString("status"))), args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
