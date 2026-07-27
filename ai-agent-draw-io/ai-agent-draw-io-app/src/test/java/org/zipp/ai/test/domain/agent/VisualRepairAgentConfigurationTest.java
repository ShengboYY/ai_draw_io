package org.zipp.ai.test.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/** Locks visual repair to its dedicated agent and narrow local tool surface. */
public class VisualRepairAgentConfigurationTest {

    @Test
    public void shouldRegisterDedicatedVisualRepairAgentWithOnlyLocalRepairTools() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("agent/agent-draw-io.yml")) {
            assertNotNull(input);
            JsonNode table = new ObjectMapper(new YAMLFactory()).readTree(input)
                    .at("/ai/agent/config/tables/drawIoVisualRepairAgent");

            assertEquals("${ZIPP_VISUAL_REPAIR_AGENT_ID:300029}",
                    table.at("/agent/agent-id").asText());
            assertEquals("agent_visual_repair_v2",
                    table.at("/module/runner/agent-name").asText());
            assertEquals(List.of("apply_visual_repair"),
                    new ObjectMapper().convertValue(
                            table.at("/module/agents/0/allowed-tools"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }));
            assertEquals("drawioVisualRepairToolCallbackProvider",
                    table.at("/module/chat-model/tool-mcp-list/0/local/name").asText());
            assertFalse(table.toString().contains("drawioSkillToolCallbackProvider"));
            assertFalse(table.toString().contains("baidu-search"));
        }
    }
}
