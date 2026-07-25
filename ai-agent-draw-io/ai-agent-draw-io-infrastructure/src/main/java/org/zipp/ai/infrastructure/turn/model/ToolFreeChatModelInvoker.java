package org.zipp.ai.infrastructure.turn.model;

import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;
import java.util.Objects;

/**
 * Invokes a fixed, fresh-session, tool-free agent. The generic legacy runtime is never exposed
 * to the application V2 ports as a mutable tool registry.
 */
public final class ToolFreeChatModelInvoker {

    private final IChatService chat;
    private final String agentId;
    private final String systemUserId;

    public ToolFreeChatModelInvoker(IChatService chat, String agentId, String systemUserId) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.agentId = required(agentId, "agentId");
        this.systemUserId = required(systemUserId, "systemUserId");
        if (!chat.isAgentToolFree(this.agentId)) {
            throw new IllegalArgumentException("configured V2 model agent must be tool-free");
        }
    }

    public String invoke(String prompt) {
        String request = required(prompt, "prompt");
        String sessionId = chat.createSession(agentId, systemUserId);
        ChatCommandEntity command = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(systemUserId)
                .sessionId(sessionId)
                .texts(List.of(new ChatCommandEntity.Content.Text(request)))
                .files(List.of())
                .inlineDatas(List.of())
                .build();
        List<String> replies = chat.handleMessage(command);
        if (replies == null || replies.isEmpty()) {
            throw new IllegalStateException("V2 model returned no output");
        }
        String output = replies.get(replies.size() - 1);
        return required(output, "model output");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
