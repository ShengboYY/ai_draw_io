package org.zipp.ai.test.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** Keeps Plain V2 model selection independent from router and visual-review models. */
public class PlainGenerationAgentConfigurationTest {

    @Test
    public void shouldAllowPlainV2ToOverrideTheSharedLanguageModel() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("agent/agent-draw-io.yml")) {
            assertNotNull(input);
            JsonNode table = new ObjectMapper(new YAMLFactory()).readTree(input)
                    .at("/ai/agent/config/tables/drawIoPlainGenerationV2Agent");

            assertEquals("${TURN_V2_PLAIN_MODEL:${LLM_MODEL:gpt-5.5}}",
                    table.at("/module/chat-model/model").asText());
        }
    }
}
