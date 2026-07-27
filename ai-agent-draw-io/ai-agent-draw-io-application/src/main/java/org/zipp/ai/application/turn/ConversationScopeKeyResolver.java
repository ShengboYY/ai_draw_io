package org.zipp.ai.application.turn;

/** Shared scope-key seam for future source/file/history migration. */
public interface ConversationScopeKeyResolver {

    ConversationScopeKeys readableScopeKeys(AuthenticatedActor actor, ConversationRef conversation);

    String newWriteScopeKey(ConversationRef conversation);
}
