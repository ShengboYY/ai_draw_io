package org.zipp.ai.test.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks automatic Memory extraction to its dedicated tool-free agent. */
public class AutoMemoryExtractionAgentConfigurationTest {

    @Test
    public void shouldRegisterDedicatedToolFreeExtractionAgent() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("agent/agent-draw-io.yml")) {
            assertNotNull(input);
            JsonNode table = new ObjectMapper(new YAMLFactory()).readTree(input)
                    .at("/ai/agent/config/tables/drawIoAutoMemoryExtractionAgent");

            assertEquals("${AUTO_MEMORY_EXTRACTOR_AGENT_ID:300030}",
                    table.at("/agent/agent-id").asText());
            assertEquals(
                    "${AUTO_MEMORY_BASE_URL:${LLM_BASE_URL_deepseek:https://api.deepseek.com}}",
                    table.at("/module/ai-api/base-url").asText());
            assertEquals("${AUTO_MEMORY_API_KEY:${LLM_API_KEY_deepseek:}}",
                    table.at("/module/ai-api/api-key").asText());
            assertEquals(
                    "${AUTO_MEMORY_COMPLETIONS_PATH:${LLM_COMPLETIONS_PATH_deepseek:v1/chat/completions}}",
                    table.at("/module/ai-api/completions-path").asText());
            assertEquals("${AUTO_MEMORY_MODEL:${LLM_MODEL_deepseek:deepseek-v4-pro}}",
                    table.at("/module/chat-model/model").asText());
            assertEquals("${AUTO_MEMORY_TEMPERATURE:0}",
                    table.at("/module/chat-model/temperature").asText());
            assertEquals("json_object",
                    table.at("/module/chat-model/response-format").asText());
            assertTrue(table.at("/module/agents/0/allowed-tools").isArray());
            assertTrue(table.at("/module/agents/0/allowed-tools").isEmpty());
            assertTrue(table.at("/module/chat-model/tool-mcp-list").isMissingNode());
            assertTrue(table.at("/module/agents/0/instruction").asText()
                    .contains("USER_TURN_DATA_JSON"));
            assertEquals("agent_auto_memory_extraction",
                    table.at("/module/runner/agent-name").asText());
        }
    }
}
