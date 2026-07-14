package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;

/** Production model adapter; its dedicated Agent must be configured to emit only the draft JSON schema. */
@Service
public class ChatEvalDraftModel implements IEvalDraftModel {
    private final IChatService chatService;
    private final String providerVersion;
    private final String agentId;
    private final String modelVersion;
    private final double temperature;

    public ChatEvalDraftModel(IChatService chatService,
                              @Value("${zipp.evaluation.text-provider-version:unconfigured}") String providerVersion,
                              @Value("${zipp.evaluation.draft-agent-id:300013}") String agentId,
                              @Value("${zipp.evaluation.draft-model-version:unconfigured}") String modelVersion,
                              @Value("${zipp.evaluation.draft-temperature:0}") double temperature) {
        this.chatService = chatService;
        this.providerVersion = providerVersion;
        this.agentId = agentId;
        this.modelVersion = modelVersion;
        this.temperature = temperature;
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
        return "provider=" + providerVersion + ":chat-agent=" + agentId
                + ":model=" + modelVersion + ":temperature=" + temperature;
    }
}
