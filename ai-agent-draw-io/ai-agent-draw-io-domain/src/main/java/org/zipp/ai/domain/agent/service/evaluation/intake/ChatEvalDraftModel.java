package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;

/** Production model adapter; its dedicated Agent must be configured to emit only the draft JSON schema. */
@Service
public class ChatEvalDraftModel implements IEvalDraftModel {
    private final IChatService chatService;
    private final String agentId;

    public ChatEvalDraftModel(IChatService chatService,
                              @Value("${zipp.evaluation.draft-agent-id:300013}") String agentId) {
        this.chatService = chatService;
        this.agentId = agentId;
    }

    @Override
    public String generate(String sanitizedPrompt) {
        String sessionId = chatService.createSession(agentId, "eval-draft-system");
        List<String> replies = chatService.handleMessage(agentId, "eval-draft-system", sessionId, sanitizedPrompt);
        if (replies == null || replies.isEmpty()) throw new IllegalStateException("draft model returned no output");
        return replies.get(replies.size() - 1);
    }

    @Override
    public String version() {
        return "chat-agent:" + agentId;
    }
}
