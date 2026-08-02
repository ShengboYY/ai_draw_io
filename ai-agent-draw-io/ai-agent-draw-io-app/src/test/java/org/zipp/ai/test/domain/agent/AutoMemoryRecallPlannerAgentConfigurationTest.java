package org.zipp.ai.test.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks multi-intent planning to a dedicated DeepSeek-backed tool-free agent. */
public class AutoMemoryRecallPlannerAgentConfigurationTest {

    @Test
    public void shouldRegisterDedicatedToolFreeRecallPlannerAgent() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("agent/agent-draw-io.yml")) {
            assertNotNull(input);
            JsonNode table = new ObjectMapper(new YAMLFactory()).readTree(input)
                    .at("/ai/agent/config/tables/drawIoAutoMemoryRecallPlannerAgent");

            assertEquals("${AUTO_MEMORY_CONTEXT_PLANNER_AGENT_ID:300031}",
                    table.at("/agent/agent-id").asText());
            assertEquals("${AUTO_MEMORY_MODEL:${LLM_MODEL_deepseek:deepseek-v4-pro}}",
                    table.at("/module/chat-model/model").asText());
            assertEquals("${AUTO_MEMORY_TEMPERATURE:0}",
                    table.at("/module/chat-model/temperature").asText());
            assertEquals("json_object",
                    table.at("/module/chat-model/response-format").asText());
            assertTrue(table.at("/module/agents/0/allowed-tools").isArray());
            assertTrue(table.at("/module/agents/0/allowed-tools").isEmpty());
            assertTrue(table.at("/module/agents/0/instruction").asText()
                    .contains("USER_REQUEST_DATA_JSON"));
            assertEquals("agent_auto_memory_recall_planning",
                    table.at("/module/runner/agent-name").asText());
        }
    }
}
