package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioMutationResultPostProcessor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RouteOnlyHistoricalRegressionTest {

    @Test
    public void repairingEdge18PreservesTheOuterWaypointsOfEdge22() throws Exception {
        String currentXml = resource("canvas/login-right-return-routing.xml");
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        String edge22Before = toolkit.edgeCells(currentXml, Set.of("22"));
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(currentXml);
        request.setTargetEdgeIds(List.of("18"));
        DrawioCanvasMcpService.DrawioMutationResponse toolResponse =
                new DrawioCanvasMcpService().optimizeDiagram(request);
        assertEquals("patch_cells", toolResponse.getType());
        assertTrue("route_only must return the requested edge",
                toolkit.edgeCells(toolResponse.getCells(), Set.of("18")).contains("id=\"18\""));
        Map<String, Object> state = new HashMap<>();
        state.put(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentXml);
        Map<String, Object> response = new HashMap<>();
        response.put("type", toolResponse.getType());
        response.put("cells", toolResponse.getCells());

        new DrawioMutationResultPostProcessor().process(
                "optimize_diagram",
                Map.of("mode", "route_only", "targetEdgeIds", List.of("18")),
                response,
                state);

        String canonical = String.valueOf(state.get(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY));
        assertEquals(edge22Before, toolkit.edgeCells(canonical, Set.of("22")));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull("Missing historical canvas fixture: " + path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
