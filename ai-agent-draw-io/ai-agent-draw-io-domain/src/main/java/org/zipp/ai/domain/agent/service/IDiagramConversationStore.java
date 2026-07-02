package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;

import java.util.Collections;
import java.util.List;

public interface IDiagramConversationStore {

    default List<DiagramConversationMessage> listMessages(String userId, String diagramId) {
        return Collections.emptyList();
    }

    default void saveMessages(List<DiagramConversationMessage> messages) {
    }

    default int deleteUserMessages(String userId) {
        return 0;
    }

}
