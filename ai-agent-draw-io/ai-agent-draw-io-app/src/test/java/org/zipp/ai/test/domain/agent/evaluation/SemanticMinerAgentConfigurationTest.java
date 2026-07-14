package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verifies that the dedicated Semantic Miner is loadable and constrained to its fixed JSON contract. */
public class SemanticMinerAgentConfigurationTest {

    @Test
    public void shouldRegisterDedicatedToolFreeSemanticMinerAgent() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("agent/agent-draw-io.yml")) {
            JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(input);
            JsonNode table = root.at("/ai/agent/config/tables/drawIoSemanticMinerAgent");

            assertFalse("Semantic Miner agent table must be registered", table.isMissingNode());
            assertEquals("${ZIPP_EVAL_SEMANTIC_MINER_AGENT_ID:300015}",
                    table.at("/agent/agent-id").asText());
            assertEquals("${ZIPP_EVAL_SEMANTIC_MINER_MODEL_VERSION:${EVAL_TEXT_LLM_MODEL:${LLM_MODEL_deepseek:deepseek-chat}}}",
                    table.at("/module/chat-model/model").asText());
            assertEquals("agent_semantic_miner", table.at("/module/agents/0/name").asText());
            assertEquals("agent_semantic_miner", table.at("/module/runner/agent-name").asText());
            assertTrue("Semantic Miner must not receive tool access",
                    table.at("/module/chat-model/tool-mcp-list").isMissingNode());

            String instruction = table.at("/module/agents/0/instruction").asText();
            assertTrue(instruction.contains("isPotentialAnomaly"));
            assertTrue(instruction.contains("requiresHumanReview"));
            assertTrue(instruction.contains("FALSE_SUCCESS"));
            assertTrue(instruction.contains("Return JSON only"));
        }
    }
}
