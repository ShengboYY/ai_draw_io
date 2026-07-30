package org.zipp.ai.test.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Keeps the configured reviewer prompt aligned with the strict visual-review schema. */
public class VisualReviewAgentConfigurationTest {

    @Test
    public void shouldRequireGroundedTargetCellIdsInTheReviewerOutput() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("agent/agent-draw-io.yml")) {
            assertNotNull(input);
            JsonNode table = new ObjectMapper(new YAMLFactory()).readTree(input)
                    .at("/ai/agent/config/tables/drawIoVisualReviewAgent");
            String instruction = table.at("/module/agents/0/instruction").asText();

            assertTrue(instruction.contains("targetCellIds contains at most 5 ids copied exactly from cellManifest"));
            assertTrue(instruction.contains("\"targetCellIds\":[]"));
            assertTrue(instruction.contains("Do not impose a preferred palette"));
            assertTrue(instruction.contains("minor spacing differences"));
            assertTrue(instruction.contains("Use minor severity for a cosmetic observation"));
            assertFalse(instruction.contains("Do not output XML, cell ids"));
        }
    }
}
