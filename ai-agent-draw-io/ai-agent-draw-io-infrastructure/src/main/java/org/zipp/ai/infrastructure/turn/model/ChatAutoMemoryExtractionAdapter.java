package org.zipp.ai.infrastructure.turn.model;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.memory.AutoMemoryExtractionDraft;
import org.zipp.ai.application.memory.AutoMemoryExtractionInput;
import org.zipp.ai.application.memory.AutoMemoryExtractionPort;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;

/** Strict tool-free adapter; model output remains an untrusted draft until application policy. */
@Component
@ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
public final class ChatAutoMemoryExtractionAdapter implements AutoMemoryExtractionPort {
    private final ToolFreeChatModelInvoker model;

    @Autowired
    public ChatAutoMemoryExtractionAdapter(
            IChatService chat,
            @Value("${app.memory.extractor-agent-id:300030}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "auto-memory-extraction"));
    }

    ChatAutoMemoryExtractionAdapter(ToolFreeChatModelInvoker model) {
        this.model = model;
    }

    @Override
    public List<AutoMemoryExtractionDraft> extract(AutoMemoryExtractionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        String output = model.invoke(
                input.modelInputBinding(),
                AutoMemoryExtractionProtocol.render(input));
        return AutoMemoryExtractionProtocol.parse(output, input);
    }
}
