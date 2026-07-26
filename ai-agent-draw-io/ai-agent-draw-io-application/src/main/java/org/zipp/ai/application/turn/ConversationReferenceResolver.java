package org.zipp.ai.application.turn;

import java.util.Objects;

/**
 * Translates an explicitly typed transport reference into a server-owned conversation.
 *
 * Bare values are rejected because they cannot distinguish a canonical id from a legacy
 * session alias without consulting two different namespaces.
 */
public final class ConversationReferenceResolver {

    public static final String DEFAULT_REFERENCE = "default";
    private static final String CANONICAL_PREFIX = "conversation:";
    private static final String LEGACY_PREFIX = "legacy:";

    public ConversationRef resolve(
            ConversationCatalogPort catalog,
            AuthenticatedActor actor,
            UserTurnCommand command
    ) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");

        return resolve(catalog, actor, command.conversationReference(), command.diagramId());
    }

    /** Resolves control-plane references without manufacturing a user-turn command. */
    public ConversationRef resolve(
            ConversationCatalogPort catalog,
            AuthenticatedActor actor,
            String conversationReference,
            String diagramId
    ) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(actor, "actor");
        ContractValues.requiredText(conversationReference, "conversationReference");
        ContractValues.requiredText(diagramId, "diagramId");

        String reference = conversationReference;
        if (DEFAULT_REFERENCE.equals(reference)) {
            return requireResolved(catalog.findOrCreateDefault(actor, diagramId));
        }
        if (reference.startsWith(CANONICAL_PREFIX)) {
            return requireResolved(catalog.requireActiveBinding(
                    actor, suffix(reference, CANONICAL_PREFIX), diagramId));
        }
        if (reference.startsWith(LEGACY_PREFIX)) {
            return requireResolved(catalog.resolveLegacyAlias(
                    actor, suffix(reference, LEGACY_PREFIX), diagramId));
        }
        throw new IllegalArgumentException("CONVERSATION_REFERENCE_AMBIGUOUS");
    }

    private String suffix(String reference, String prefix) {
        String value = reference.substring(prefix.length());
        if (value.isBlank()) {
            throw new IllegalArgumentException("CONVERSATION_REFERENCE_EMPTY");
        }
        return value;
    }

    private ConversationRef requireResolved(ConversationRef conversation) {
        return Objects.requireNonNull(conversation, "conversation catalog returned null");
    }
}
