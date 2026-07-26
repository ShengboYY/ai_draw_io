package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationScopeKeyResolver;
import org.zipp.ai.application.turn.ConversationScopeKeys;
import org.zipp.ai.application.turn.ConversationStatus;

import java.util.List;
import java.util.Objects;

/** Reads the server-owned canonical conversation and its bounded legacy aliases. */
@Repository
public class MySqlConversationScopeKeyResolver implements ConversationScopeKeyResolver {

    private static final String SELECT_ALIASES = """
            SELECT a.legacy_session_id
            FROM conversation_legacy_alias a
            INNER JOIN conversation c ON c.id = a.conversation_id
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE a.owner_key = ? AND a.conversation_id = ?
              AND c.owner_key = ? AND c.status = 'ACTIVE' AND d.deleted = 0
            ORDER BY a.created_at ASC, a.legacy_session_id ASC
            LIMIT %d
            """.formatted(ConversationScopeKeys.MAX_LEGACY_ALIASES + 1);
    private static final String SELECT_CANONICAL = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation c
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE c.id = ? AND c.owner_key = ? AND c.status = 'ACTIVE' AND d.deleted = 0
            """;
    private static final String SELECT_CANONICAL_FOR_DIAGRAM = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation c
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE c.id = ? AND c.owner_key = ? AND c.diagram_id = ?
              AND c.status = 'ACTIVE' AND d.deleted = 0
            """;
    private static final String SELECT_ALIAS = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation_legacy_alias a
            INNER JOIN conversation c ON c.id = a.conversation_id
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE a.owner_key = ? AND a.legacy_session_id = ?
              AND c.status = 'ACTIVE' AND d.deleted = 0
            """;
    private static final String SELECT_ALIAS_FOR_DIAGRAM = SELECT_ALIAS + " AND c.diagram_id = ?\n";
    private static final String SELECT_DEFAULT = """
            SELECT c.id, c.owner_key, c.diagram_id, c.status
            FROM conversation c
            INNER JOIN diagram d ON d.id = c.diagram_id AND d.user_id = c.owner_key
            WHERE c.owner_key = ? AND c.diagram_id = ?
              AND c.status = 'ACTIVE' AND d.deleted = 0
            ORDER BY c.updated_at ASC, c.id ASC
            LIMIT 1
            """;

    private final JdbcOperations jdbc;

    public MySqlConversationScopeKeyResolver(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ConversationScopeKeys readableScopeKeys(AuthenticatedActor actor, ConversationRef conversation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(conversation, "conversation");
        if (!actor.ownerKey().equals(conversation.ownerKey()) || !conversation.isActive()) {
            throw new IllegalStateException("CONVERSATION_SCOPE_NOT_ACTIVE");
        }
        List<String> aliases = jdbc.query(
                SELECT_ALIASES,
                (resultSet, rowNumber) -> resultSet.getString("legacy_session_id"),
                actor.ownerKey(), conversation.id(), conversation.ownerKey());
        // LIMIT MAX+1 deliberately lets the resolver distinguish an over-bounded mapping from
        // a complete read set; callers must fail closed rather than silently truncating history.
        if (aliases.size() > ConversationScopeKeys.MAX_LEGACY_ALIASES) {
            throw new IllegalStateException("CONVERSATION_SCOPE_ALIAS_LIMIT_EXCEEDED");
        }
        return new ConversationScopeKeys(conversation.id(), aliases);
    }

    @Override
    public String newWriteScopeKey(ConversationRef conversation) {
        Objects.requireNonNull(conversation, "conversation");
        if (!conversation.isActive()) {
            throw new IllegalStateException("CONVERSATION_SCOPE_NOT_ACTIVE");
        }
        return conversation.id();
    }

    /** Resolves a raw compatibility reference before a scope consumer performs a dual read. */
    public ConversationScopeKeys readableScopeKeys(
            AuthenticatedActor actor,
            String rawReference,
            String diagramId
    ) {
        Objects.requireNonNull(actor, "actor");
        requireText(rawReference, "conversationReference");
        ConversationRef conversation = findRaw(actor, rawReference, diagramId);
        return readableScopeKeys(actor, conversation);
    }

    /** Resolves a raw reference and returns only the canonical key for new writes. */
    public String newWriteScopeKey(AuthenticatedActor actor, String rawReference, String diagramId) {
        Objects.requireNonNull(actor, "actor");
        requireText(rawReference, "conversationReference");
        return newWriteScopeKey(findRaw(actor, rawReference, diagramId));
    }

    private ConversationRef findRaw(AuthenticatedActor actor, String rawReference, String diagramId) {
        if ("default".equals(rawReference)) {
            if (isBlank(diagramId)) {
                throw new IllegalArgumentException("diagramId is required for default conversation");
            }
            List<ConversationRef> defaults = jdbc.query(
                    SELECT_DEFAULT,
                    (resultSet, rowNumber) -> conversation(resultSet.getString("id"),
                            resultSet.getString("owner_key"), resultSet.getString("diagram_id"),
                            resultSet.getString("status")),
                    actor.ownerKey(), diagramId);
            if (defaults.size() != 1) {
                throw new IllegalStateException("CONVERSATION_DEFAULT_UNRESOLVED");
            }
            return defaults.get(0);
        }
        List<ConversationRef> canonical = jdbc.query(
                isBlank(diagramId) ? SELECT_CANONICAL : SELECT_CANONICAL_FOR_DIAGRAM,
                (resultSet, rowNumber) -> conversation(resultSet.getString("id"),
                        resultSet.getString("owner_key"), resultSet.getString("diagram_id"),
                        resultSet.getString("status")),
                isBlank(diagramId)
                        ? new Object[]{rawReference, actor.ownerKey()}
                        : new Object[]{rawReference, actor.ownerKey(), diagramId});
        if (!canonical.isEmpty()) {
            return canonical.get(0);
        }
        List<ConversationRef> aliases = jdbc.query(
                isBlank(diagramId) ? SELECT_ALIAS : SELECT_ALIAS_FOR_DIAGRAM,
                (resultSet, rowNumber) -> conversation(resultSet.getString("id"),
                        resultSet.getString("owner_key"), resultSet.getString("diagram_id"),
                        resultSet.getString("status")),
                isBlank(diagramId)
                        ? new Object[]{actor.ownerKey(), rawReference}
                        : new Object[]{actor.ownerKey(), rawReference, diagramId});
        if (aliases.size() != 1) {
            throw new IllegalStateException("CONVERSATION_SCOPE_REFERENCE_UNRESOLVED");
        }
        return aliases.get(0);
    }

    private ConversationRef conversation(String id, String ownerKey, String diagramId, String status) {
        return new ConversationRef(id, ownerKey, diagramId, ConversationStatus.valueOf(status));
    }

    private static void requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
