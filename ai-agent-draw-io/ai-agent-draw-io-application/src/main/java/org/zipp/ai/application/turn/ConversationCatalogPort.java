package org.zipp.ai.application.turn;

/** Resolves server-owned canonical conversation identity before turn admission. */
public interface ConversationCatalogPort {

    ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId);

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
