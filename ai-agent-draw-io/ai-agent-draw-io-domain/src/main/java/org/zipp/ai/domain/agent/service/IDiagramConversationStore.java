package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public interface IDiagramConversationStore {

    default List<DiagramConversationMessage> listMessages(String userId, String diagramId) {
        return Collections.emptyList();
    }

    /** Scope-aware read; the two-argument method remains the legacy default conversation view. */
    default List<DiagramConversationMessage> listMessages(
            String userId, String diagramId, String conversationReference) {
        return listMessages(userId, diagramId);
    }

    /** Reads one durable assistant record without re-resolving a legacy alias. */
    default Optional<DiagramConversationMessage> findAssistantMessage(
            String userId, String diagramId, String canonicalConversationId, String turnId) {
        return Optional.empty();
    }

    default void saveMessages(List<DiagramConversationMessage> messages) {
    }

    default int deleteUserMessages(String userId) {
        return 0;
    }

}
