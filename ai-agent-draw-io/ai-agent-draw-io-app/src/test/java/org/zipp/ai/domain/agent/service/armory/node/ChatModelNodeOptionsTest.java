package org.zipp.ai.domain.agent.service.armory.node;

import org.junit.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Verifies that versioned Agent sampling settings reach the provider request options. */
public class ChatModelNodeOptionsTest {

    @Test
    public void configuredTemperatureIsAppliedToDefaultOptions() {
        AiAgentConfigTableVO.Module.ChatModel config = new AiAgentConfigTableVO.Module.ChatModel();
        config.setModel("judge-model");
        config.setTemperature(0D);

        OpenAiChatOptions options = ChatModelNode.defaultOptions(config);

        assertEquals("judge-model", options.getModel());
        assertEquals(Double.valueOf(0D), options.getTemperature());
    }

    @Test
    public void missingTemperaturePreservesProviderDefault() {
        AiAgentConfigTableVO.Module.ChatModel config = new AiAgentConfigTableVO.Module.ChatModel();
        config.setModel("production-model");

        assertNull(ChatModelNode.defaultOptions(config).getTemperature());
    }
}
