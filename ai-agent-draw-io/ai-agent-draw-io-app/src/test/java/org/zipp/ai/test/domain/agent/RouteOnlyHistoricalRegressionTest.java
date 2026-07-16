package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioMutationResultPostProcessor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RouteOnlyHistoricalRegressionTest {

    private static final Pattern WAYPOINT_X = Pattern.compile("<mxPoint x=\"(-?\\d+(?:\\.\\d+)?)\"");
    private static final Pattern WAYPOINT_Y = Pattern.compile("y=\"(-?\\d+(?:\\.\\d+)?)\"");

    @Test
    public void repairingEdge18PreservesTheOuterWaypointsOfEdge22() throws Exception {
        String currentXml = resource("canvas/login-right-return-routing.xml");
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        String edge22Before = toolkit.edgeCells(currentXml, Set.of("22"));
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setDiagramType("flowchart");
        request.setXml(currentXml);
        request.setTargetEdgeIds(List.of("18"));
        DrawioCanvasMcpService.DrawioMutationResponse toolResponse = v2Service().optimizeDiagram(request);
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

    @Test
    public void repairingInnerRightReturnUsesADistinctOuterGutterAndIsIdempotent() throws Exception {
        String currentXml = resource("canvas/login-right-return-routing.xml");
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();

        DrawioCanvasMcpService.DrawioMutationResponse first = optimize(currentXml, "flowchart", "18");
        String firstEdge = toolkit.edgeCells(first.getCells(), Set.of("18"));
        assertTrue("the repaired return must leave the node field through the right outer gutter",
                maximumWaypointX(firstEdge) > 930D);

        String onceRepaired = toolkit.replaceCells(currentXml, first.getCells());
        DrawioCanvasMcpService.DrawioMutationResponse second = optimize(onceRepaired, "flowchart", "18");
        assertEquals(firstEdge, toolkit.edgeCells(second.getCells(), Set.of("18")));
    }

    @Test
    public void routerV2RemainsDisabledByDefaultUntilShadowValidation() throws Exception {
        String currentXml = resource("canvas/login-right-return-routing.xml");
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setDiagramType("flowchart");
        request.setXml(currentXml);
        request.setTargetEdgeIds(List.of("18"));

        DrawioCanvasMcpService.DrawioMutationResponse response =
                new DrawioCanvasMcpService().optimizeDiagram(request);

        assertEquals("patch_cells", response.getType());
        assertTrue("the default path must still use router v1 while the v2 flag is off",
                maximumWaypointX(response.getCells()) < 890D);
    }

    @Test
    public void aValidExplicitOuterRouteKeepsItsPortsAndWaypoints() throws Exception {
        String currentXml = resource("canvas/login-right-return-routing.xml");
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        String before = toolkit.edgeCells(currentXml, Set.of("22"));

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(currentXml, "flowchart", "22");

        assertEquals(before, toolkit.edgeCells(response.getCells(), Set.of("22")));
    }

    @Test
    public void aLeftSideReturnMayUseTheLeftOuterGutter() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="top" value="Top" vertex="1" parent="1"><mxGeometry x="400" y="100" width="160" height="60" as="geometry"/></mxCell>
                <mxCell id="failure" value="Retry" vertex="1" parent="1"><mxGeometry x="100" y="360" width="160" height="60" as="geometry"/></mxCell>
                <mxCell id="return" value="retry" style="edgeStyle=orthogonalEdgeStyle;dashed=1;exitX=1;exitY=0.5;entryX=0;entryY=0.5;" edge="1" parent="1" source="failure" target="top">
                  <mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="330" y="390"/><mxPoint x="330" y="130"/></Array></mxGeometry>
                </mxCell></root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "flowchart", "return");

        assertTrue("a return whose source is on the left should be allowed to use the left gutter",
                minimumWaypointX(response.getCells()) < 100D);
    }

    @Test
    public void unsupportedSequenceProfileFailsClosedWithoutRewritingTheEdge() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="a" value="A" vertex="1" parent="1"><mxGeometry x="100" y="100" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="b" value="B" vertex="1" parent="1"><mxGeometry x="400" y="100" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="message" style="edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.5;entryX=0;entryY=0.5;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;
        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "sequence", "message");

        assertEquals("no_safe_candidate", response.getType());
        assertNull(response.getCells());
        assertTrue(response.getRepairBrief().contains("NO_SAFE_CANDIDATE"));
    }

    @Test
    public void aHorizontalArchitectureReturnUsesAnOuterVerticalGutter() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="target" value="Target" vertex="1" parent="1"><mxGeometry x="100" y="100" width="140" height="60" as="geometry"/></mxCell>
                <mxCell id="source" value="Source" vertex="1" parent="1"><mxGeometry x="500" y="100" width="140" height="60" as="geometry"/></mxCell>
                <mxCell id="return" value="response" style="edgeStyle=orthogonalEdgeStyle;dashed=1;" edge="1" parent="1" source="source" target="target"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "architecture", "return");

        assertTrue("a left-running edge in a layered layout should use the top or bottom gutter",
                minimumWaypointY(response.getCells()) < 100D || maximumWaypointY(response.getCells()) > 160D);
    }

    @Test
    public void nestedNodeCoordinatesAreResolvedBeforeChoosingTheGutter() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="container" value="Group" style="swimlane;container=1;" vertex="1" parent="1"><mxGeometry x="300" y="100" width="300" height="420" as="geometry"/></mxCell>
                <mxCell id="target" value="Target" vertex="1" parent="container"><mxGeometry x="50" y="30" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="source" value="Source" vertex="1" parent="container"><mxGeometry x="50" y="300" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="return" value="retry" style="edgeStyle=orthogonalEdgeStyle;dashed=1;" edge="1" parent="container" source="source" target="target"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "flowchart", "return");
        double lane = minimumWaypointX(response.getCells());

        assertTrue("the gutter must be based on the child's absolute x=350, not its local x=50",
                lane > 250D && lane < 350D);
    }

    @Test
    public void multipleReturnEdgesReceiveDistinctOuterLanes() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="target" value="Input" vertex="1" parent="1"><mxGeometry x="400" y="100" width="140" height="60" as="geometry"/></mxCell>
                <mxCell id="failure1" value="Retry one" vertex="1" parent="1"><mxGeometry x="700" y="320" width="160" height="60" as="geometry"/></mxCell>
                <mxCell id="failure2" value="Retry two" vertex="1" parent="1"><mxGeometry x="700" y="500" width="160" height="60" as="geometry"/></mxCell>
                <mxCell id="r1" value="retry" style="edgeStyle=orthogonalEdgeStyle;dashed=1;" edge="1" parent="1" source="failure1" target="target"><mxGeometry relative="1" as="geometry"/></mxCell>
                <mxCell id="r2" value="retry" style="edgeStyle=orthogonalEdgeStyle;dashed=1;" edge="1" parent="1" source="failure2" target="target"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "flowchart", "r1", "r2");
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        String r1 = toolkit.edgeCells(response.getCells(), Set.of("r1"));
        String r2 = toolkit.edgeCells(response.getCells(), Set.of("r2"));
        double r1Lane = maximumWaypointX(r1);
        double r2Lane = maximumWaypointX(r2);

        assertTrue("r1 must use an outer lane: " + r1,
                minimumWaypointX(r1) < 400D || r1Lane > 860D);
        assertTrue("r2 must use an outer lane: " + r2,
                minimumWaypointX(r2) < 400D || r2Lane > 860D);
        assertTrue("return edges must not share a collinear outer lane", Math.abs(r1Lane - r2Lane) >= 24D);
    }

    @Test
    public void bidirectionalEdgesDoNotCollapseOntoTheSameTrack() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="a" value="A" vertex="1" parent="1"><mxGeometry x="100" y="100" width="120" height="80" as="geometry"/></mxCell>
                <mxCell id="b" value="B" vertex="1" parent="1"><mxGeometry x="500" y="100" width="120" height="80" as="geometry"/></mxCell>
                <mxCell id="ab" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
                <mxCell id="ba" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="b" target="a"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "architecture", "ab", "ba");
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        String ab = toolkit.edgeCells(response.getCells(), Set.of("ab"));
        String ba = toolkit.edgeCells(response.getCells(), Set.of("ba"));

        assertTrue(ab, ab.contains("exitY=0.35"));
        assertTrue("the reverse edge should use an outer vertical gutter: " + ba,
                minimumWaypointY(ba) < 100D || maximumWaypointY(ba) > 180D);
    }

    @Test
    public void aSelfLoopReturnsNoSafeCandidateInsteadOfInventingAGlobalRoute() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="state" value="State" vertex="1" parent="1"><mxGeometry x="100" y="100" width="140" height="60" as="geometry"/></mxCell>
                <mxCell id="loop" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="state" target="state"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "state", "loop");

        assertEquals("no_safe_candidate", response.getType());
        assertNull(response.getCells());
        assertTrue(response.getRepairBrief().contains("NO_SAFE_CANDIDATE"));
    }

    @Test
    public void ordinaryRoutingUsesObstacleBoundaryCorridors() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="a" value="A" vertex="1" parent="1"><mxGeometry x="100" y="100" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="blocker" value="Blocker" vertex="1" parent="1"><mxGeometry x="300" y="80" width="140" height="100" as="geometry"/></mxCell>
                <mxCell id="b" value="B" vertex="1" parent="1"><mxGeometry x="520" y="100" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="edge" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "architecture", "edge");

        assertTrue("the route must leave the blocked center corridor: " + response.getCells(),
                minimumWaypointY(response.getCells()) < 80D || maximumWaypointY(response.getCells()) > 180D);
    }

    @Test
    public void explicitReturnRoleOverridesForwardDirectionFallback() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="a" value="A" vertex="1" parent="1"><mxGeometry x="400" y="100" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="b" value="B" vertex="1" parent="1"><mxGeometry x="400" y="360" width="120" height="60" as="geometry"/></mxCell>
                <mxCell id="edge" edgeRole="RETURN" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "flowchart", "edge");

        assertTrue("explicit RETURN metadata must select an outer gutter: " + response.getCells(),
                minimumWaypointX(response.getCells()) < 400D || maximumWaypointX(response.getCells()) > 520D);
    }

    @Test
    public void generatedPortsStayAwayFromNodeCorners() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="target" value="Target" vertex="1" parent="1"><mxGeometry x="400" y="100" width="140" height="80" as="geometry"/></mxCell>
                <mxCell id="source" value="Retry" vertex="1" parent="1"><mxGeometry x="700" y="360" width="140" height="80" as="geometry"/></mxCell>
                <mxCell id="return" style="edgeStyle=orthogonalEdgeStyle;exitY=0.01;entryY=0.99;" edge="1" parent="1" source="source" target="target"><mxGeometry relative="1" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        DrawioCanvasMcpService.DrawioMutationResponse response = optimize(xml, "flowchart", "return");

        assertTrue(response.getCells(), response.getCells().contains("exitY=0.25"));
        assertTrue(response.getCells(), response.getCells().contains("entryY=0.75"));
    }

    private DrawioCanvasMcpService.DrawioMutationResponse optimize(String xml, String diagramType, String... edgeIds) {
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(xml);
        request.setTargetEdgeIds(List.of(edgeIds));
        request.setDiagramType(diagramType);
        return v2Service().optimizeDiagram(request);
    }

    private DrawioCanvasMcpService v2Service() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        ReflectionTestUtils.setField(service, "targetedEdgeRouterV2Enabled", true);
        return service;
    }

    private double maximumWaypointX(String edgeXml) {
        double maximum = -Double.MAX_VALUE;
        Matcher matcher = WAYPOINT_X.matcher(edgeXml);
        while (matcher.find()) {
            maximum = Math.max(maximum, Double.parseDouble(matcher.group(1)));
        }
        return maximum;
    }

    private double minimumWaypointX(String edgeXml) {
        double minimum = Double.MAX_VALUE;
        Matcher matcher = WAYPOINT_X.matcher(edgeXml);
        while (matcher.find()) {
            minimum = Math.min(minimum, Double.parseDouble(matcher.group(1)));
        }
        return minimum;
    }

    private double maximumWaypointY(String edgeXml) {
        double maximum = -Double.MAX_VALUE;
        Matcher matcher = WAYPOINT_Y.matcher(edgeXml);
        while (matcher.find()) {
            maximum = Math.max(maximum, Double.parseDouble(matcher.group(1)));
        }
        return maximum;
    }

    private double minimumWaypointY(String edgeXml) {
        double minimum = Double.MAX_VALUE;
        Matcher matcher = WAYPOINT_Y.matcher(edgeXml);
        while (matcher.find()) {
            minimum = Math.min(minimum, Double.parseDouble(matcher.group(1)));
        }
        return minimum;
    }

    private String resource(String path) throws Exception {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull("Missing historical canvas fixture: " + path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
