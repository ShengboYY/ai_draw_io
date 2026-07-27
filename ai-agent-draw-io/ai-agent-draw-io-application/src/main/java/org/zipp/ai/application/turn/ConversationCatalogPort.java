package org.zipp.ai.application.turn;

/** Resolves server-owned canonical conversation identity before turn admission. */
public interface ConversationCatalogPort {

    ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId);

    /**
     * Resolves the diagram's default conversation and atomically binds a newly issued
     * legacy runtime session so subsequent compatibility reads resolve to the same identity.
     */
    default ConversationRef findOrCreateDefaultForLegacySession(
            AuthenticatedActor actor,
            String legacySessionId,
            String diagramId
    ) {
        throw new UnsupportedOperationException("LEGACY_CONVERSATION_ALIAS_BINDING_NOT_SUPPORTED");
    }

    ConversationRef requireActiveBinding(
            AuthenticatedActor actor,
            String conversationId,
            String diagramId
    );

    ConversationRef resolveLegacyAlias(
            AuthenticatedActor actor,
            String legacySessionId,
            String diagramId
    );
}
