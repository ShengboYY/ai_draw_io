package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioMutationResultPostProcessor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DrawioMutationResultPostProcessorTest {

    @Test
    public void patchResponseIsAnalyzedAgainstTheCompleteSeededCanvas() {
        String currentXml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Worker' vertex='1' parent='1'><mxGeometry x='340' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;
        Map<String, Object> state = new HashMap<>();
        state.put(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentXml);
        Map<String, Object> response = new HashMap<>();
        response.put("type", "patch_cells");
        response.put("cells", "<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>");

        Map<String, Object> processed = new DrawioMutationResultPostProcessor().process(
                "modify_diagram",
                Map.of("mode", "patch"),
                response,
                state);

        String canonical = String.valueOf(state.get(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY));
        assertTrue(canonical.contains("API v2"));
        assertTrue(canonical.contains("Worker"));
        assertFalse(processed.containsKey("content"));
        assertNotNull(processed.get("analysis"));
        assertNotNull(processed.get("repairBrief"));
    }
}
