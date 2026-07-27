package org.zipp.ai.test.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks uploaded material observation to a dedicated tool-free multimodal agent. */
public class MaterialVisualObservationAgentConfigurationTest {

    @Test
    public void shouldRegisterDedicatedToolFreeVisualObservationAgent() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("agent/agent-draw-io.yml")) {
            assertNotNull(input);
            JsonNode table = new ObjectMapper(new YAMLFactory()).readTree(input)
                    .at("/ai/agent/config/tables/drawIoMaterialVisualObservationAgent");

            assertEquals("${MATERIAL_VISUAL_OBSERVATION_AGENT_ID:300021}",
                    table.at("/agent/agent-id").asText());
            assertEquals("${VLM_MODEL:${LLM_MODEL:gpt-5.5}}",
                    table.at("/module/chat-model/model").asText());
            assertTrue(table.at("/module/agents/0/allowed-tools").isArray());
            assertTrue(table.at("/module/agents/0/allowed-tools").isEmpty());
            assertTrue(table.at("/module/chat-model/tool-mcp-list").isMissingNode());
            assertEquals("agent_material_visual_observer",
                    table.at("/module/runner/agent-name").asText());
        }
    }
}
