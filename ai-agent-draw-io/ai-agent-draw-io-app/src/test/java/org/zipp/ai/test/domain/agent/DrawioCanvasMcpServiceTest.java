package org.zipp.ai.test.domain.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.junit.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.usage.AgentTelemetryMetrics;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioCanvasMcpServiceTest {

    @Test
    public void shouldExposeOnlyConsolidatedToolsToLlm() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new DrawioCanvasMcpService())
                .build()
                .getToolCallbacks();

        List<String> toolNames = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertEquals(Set.of("create_diagram", "modify_diagram", "optimize_diagram"), Set.copyOf(toolNames));
        assertFalse(toolNames.contains("inspect_canvas"));
        assertFalse(toolNames.contains("display_diagram"));
        assertFalse(toolNames.contains("patch_cells"));
        assertFalse(toolNames.contains("validate_diagram"));
        assertFalse(toolNames.contains("route_edges"));
    }

    @Test
    public void shouldReturnPatchCellsFromConsolidatedModifyTool() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("patch");
        request.setCells("<mxCell id='2' value='Gateway' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertEquals("patch_cells", response.getType());
        assertTrue(response.getCells().contains("Gateway"));
        assertEquals(null, response.getAnalysis());
    }

    @Test
    public void shouldReplaceCellsFromConsolidatedModifyTool() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("replace_cells");
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Old API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);
        request.setCells("<mxCell id='2' value='Gateway' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertTrue(response.getContent().contains("Gateway"));
        assertFalse(response.getContent().contains("Old API"));
    }

    @Test
    public void shouldReturnPatchCellsForAppendModifyTool() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("append");
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);
        request.setCells("""
                <mxCell id='3' value='Worker' vertex='1' parent='1'><mxGeometry x='320' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='dispatches' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                """);

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertEquals("patch_cells", response.getType());
        assertEquals(null, response.getContent());
        assertTrue(response.getCells().contains("Worker"));
        assertEquals("validation_result", response.getAnalysis().getType());
        assertEquals(2, response.getAnalysis().getSummary().getNodeCount());
        assertEquals(1, response.getAnalysis().getSummary().getEdgeCount());
        String merged = new DrawioCanvasXmlToolkit().replaceCells(request.getXml(), response.getCells());
        assertTrue(merged.contains("API"));
        assertTrue(merged.contains("Worker"));
    }

    @Test
    public void shouldAttachAnalysisToCreateDiagramOutput() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='150' y='120' width='120' height='60' as='geometry'/></mxCell>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertEquals("validation_result", response.getAnalysis().getType());
        assertEquals(false, response.getAnalysis().isValid());
        assertEquals(2, response.getAnalysis().getSummary().getNodeCount());
        assertTrue(response.getAnalysis().getIssues().stream()
                .anyMatch(issue -> "NODE_OVERLAP".equals(issue.getType()) && issue.getTargetCellIds().equals(List.of("2", "3"))));
    }

    @Test
    public void shouldLeaveRawLabelCanonicalizationToTheMutationGate() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxCell id='2' value='<heap & metaspace>' vertex='1' parent='1'><mxGeometry x='100' y='100' width='180' height='70' as='geometry'/></mxCell>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertEquals(false, response.getAnalysis().isValid());
        assertTrue(response.getContent().contains("value='<heap & metaspace>'"));
    }

    @Test
    public void shouldAttachAnalysisToFullModifyOutput() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("replace_cells");
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Old API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);
        request.setCells("<mxCell id='2' value='Gateway' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertEquals("validation_result", response.getAnalysis().getType());
        assertEquals(true, response.getAnalysis().isValid());
        assertEquals(1, response.getAnalysis().getSummary().getNodeCount());
        assertEquals(0, response.getAnalysis().getSummary().getEdgeCount());
    }

    @Test
    public void shouldReturnLayoutOptimizeAsAnUnmodifiedWorkingCandidate() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("layout_optimize");
        String candidate = edgeCrossingGraphXml();
        request.setXml(candidate);

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertEquals("validation_result", response.getAnalysis().getType());
        assertEquals(candidate, response.getContent());
    }

    @Test
    public void shouldPreserveDrawerWaypointsDuringLayoutOptimize() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("layout_optimize");
        String drawerXml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Main step' vertex='1' parent='1'><mxGeometry x='420' y='360' width='160' height='70' as='geometry'/></mxCell>
                <mxCell id='3' value='Failure' vertex='1' parent='1'><mxGeometry x='720' y='360' width='160' height='70' as='geometry'/></mxCell>
                <mxCell id='22' value='retry' style='edgeStyle=orthogonalEdgeStyle;dashed=1;exitX=1;exitY=0.5;entryX=1;entryY=0.5;' edge='1' parent='1' source='3' target='2'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='930' y='395'/><mxPoint x='930' y='300'/><mxPoint x='620' y='300'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """;
        request.setXml(drawerXml);

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("drawio_done", response.getType());
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        assertEquals(toolkit.edgeCells(drawerXml, Set.of("22")),
                toolkit.edgeCells(response.getContent(), Set.of("22")));
    }

    @Test
    public void shouldReturnEdgePatchForRouteOnlyOptimize() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(edgeCrossingGraphXml());
        request.setTargetEdgeIds(List.of("5"));

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("patch_cells", response.getType());
        assertTrue(response.getCells().contains("edge='1'") || response.getCells().contains("edge=\"1\""));
        assertFalse(response.getCells().contains("vertex='1'") || response.getCells().contains("vertex=\"1\""));
        assertEquals("validation_result", response.getAnalysis().getType());
        assertNoAnalyzerIssue(new DrawioCanvasXmlToolkit().replaceCells(edgeCrossingGraphXml(), response.getCells()),
                CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
    }

    @Test
    public void shouldPublishTargetedRouterVersionAndOutcome() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        inject(service, "targetedEdgeRouterV2Enabled", true);
        inject(service, "agentUsageTelemetryService", new AgentUsageTelemetryService(
                new FakeAgentUsageTelemetryStore(), java.time.Clock.systemUTC(),
                AgentUsageTelemetryService.TelemetryWriteExecutor.direct(),
                new AgentTelemetryMetrics(registry)));
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(edgeCrossingGraphXml());
        request.setDiagramType("flowchart");
        request.setTargetEdgeIds(List.of("5"));

        service.optimizeDiagram(request);

        assertEquals(1D, registry.get("ai.agent.targeted.edge.router")
                .tags("version", "v2", "outcome", "routed")
                .counter().count(), 0D);
    }

    @Test
    public void shouldRejectRouteOnlyOptimizeWithoutTargetEdgeIds() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(edgeCrossingGraphXml());

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("tool_error", response.getType());
        assertTrue(response.getMessage().contains("targetEdgeIds"));
        assertEquals(null, response.getCells());
        assertEquals(null, response.getContent());
    }

    @Test
    public void shouldRejectRouteOnlyTargetsThatAreNotExistingEdges() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(edgeCrossingGraphXml());
        request.setTargetEdgeIds(List.of("4", "404"));

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("tool_error", response.getType());
        assertTrue(response.getMessage().contains("existing edge"));
        assertTrue(response.getMessage().contains("4"));
        assertTrue(response.getMessage().contains("404"));
    }

    @Test
    public void shouldReturnOnlyTargetedEdgesFromRouteOnlyOptimize() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setTargetEdgeIds(List.of("5"));
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='primary' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='6' value='manual return' style='edgeStyle=orthogonalEdgeStyle;exitX=0;exitY=0.5;entryX=1;entryY=0.5;' edge='1' parent='1' source='3' target='2'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='320' y='240'/><mxPoint x='160' y='240'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("patch_cells", response.getType());
        assertTrue(response.getCells().contains("id=\"5\""));
        assertFalse("route_only must not return collateral edge patches",
                response.getCells().contains("id=\"6\""));
    }

    @Test
    public void shouldOptimizeStoredCanvasWhenXmlIsNotProvided() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        injectCanvasStateStore(service, new FixedCanvasStateStore(edgeCrossingGraphXml()));
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setUserId("alice");
        request.setDiagramId("diagram-1");
        request.setTargetEdgeIds(List.of("5"));

        DrawioCanvasMcpService.DrawioMutationResponse response = service.optimizeDiagram(request);

        assertEquals("patch_cells", response.getType());
        assertTrue(response.getCells().contains("edge='1'") || response.getCells().contains("edge=\"1\""));
        assertNoAnalyzerIssue(new DrawioCanvasXmlToolkit().replaceCells(edgeCrossingGraphXml(), response.getCells()),
                CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
    }

    @Test
    public void shouldAutoRouteCreateDiagramCrossingsBeforeReturningAnalysis() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml(edgeCrossingGraphXml());

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        // Deterministic problems get deterministic fixes: the crossing is rerouted before the
        // draft is analyzed and streamed, so neither the loop nor the user ever sees it.
        assertEquals("drawio_done", response.getType());
        assertTrue(response.getAnalysis().getIssues().stream()
                .noneMatch(issue -> "EDGE_NODE_CROSSING".equals(issue.getType())));
    }

    @Test
    public void shouldRejectFullXmlModifyDiagram() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("full_xml");
        request.setXml(edgeCrossingGraphXml());

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertModifyRejected(response);
    }

    @Test
    public void shouldRejectXmlOnlyModifyDiagramInsteadOfDefaultingToFullXml() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setXml(edgeCrossingGraphXml());

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertModifyRejected(response);
    }

    @Test
    public void shouldInspectCanvasWithStateValidationAndOverlapData() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='150' y='120' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.InspectCanvasResponse response = service.inspectCanvas(request);

        assertEquals("canvas_inspection", response.getType());
        assertEquals(false, response.isValid());
        assertEquals(2, response.getNodeCount());
        assertEquals(0, response.getEdgeCount());
        assertEquals(1, response.getOverlaps().size());
        assertTrue(response.getIssues().stream().anyMatch(issue -> issue.contains("Overlapping nodes: 2 and 3")));
    }

    @Test
    public void shouldReturnCellFragmentsAsUnacceptedWorkingCandidates() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("<mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioToolResponse response = service.displayDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertFalse(response.getContent().contains("<mxGraphModel>"));
        assertTrue(response.getContent().contains("value='API'"));
    }

    @Test
    public void shouldPreserveCompleteGraphModel() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>");

        DrawioCanvasMcpService.DrawioToolResponse response = service.editDiagram(request);

        assertEquals("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>", response.getContent());
    }

    @Test
    public void shouldValidateMissingEdgeTarget() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='missing' edge='1' parent='1' source='2' target='404'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioValidationResponse response = service.validateDiagram(request);

        assertEquals("validation_result", response.getType());
        assertEquals(false, response.isValid());
        assertEquals("critical", response.getSeverity());
        assertTrue(response.getIssues().stream().anyMatch(issue -> issue.contains("target id does not exist: 404")));
    }

    @Test
    public void shouldReturnCanvasStateAndFindCells() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        String xml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API Gateway' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='70' as='geometry'/></mxCell>
                <mxCell id='3' value='Service' vertex='1' parent='1'><mxGeometry x='340' y='100' width='160' height='70' as='geometry'/></mxCell>
                <mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;
        DrawioCanvasMcpService.DrawioXmlRequest stateRequest = new DrawioCanvasMcpService.DrawioXmlRequest();
        stateRequest.setXml(xml);

        DrawioCanvasMcpService.DrawioCanvasStateResponse state = service.getCanvasState(stateRequest);

        assertEquals("canvas_state", state.getType());
        assertEquals(2, state.getNodeCount());
        assertEquals(1, state.getEdgeCount());
        assertTrue(state.getSummary().contains("2 nodes and 1 edges"));

        DrawioCanvasMcpService.FindCellsRequest findRequest = new DrawioCanvasMcpService.FindCellsRequest();
        findRequest.setXml(xml);
        findRequest.setQuery("gateway");

        DrawioCanvasMcpService.FindCellsResponse matches = service.findCells(findRequest);

        assertEquals("cell_matches", matches.getType());
        assertEquals(1, matches.getMatches().size());
        assertEquals("2", matches.getMatches().get(0).getId());
        assertEquals("API Gateway", matches.getMatches().get(0).getLabel());
    }

    @Test
    public void shouldUpdateOnlyProvidedCells() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.UpdateCellsRequest request = new DrawioCanvasMcpService.UpdateCellsRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Old API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Service' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);
        request.setCells("<mxCell id='2' value='New API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='70' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioToolResponse response = service.updateCells(request);

        assertEquals("drawio_done", response.getType());
        assertTrue(response.getContent().contains("value=\"New API\""));
        assertTrue(response.getContent().contains("value=\"Service\""));
        assertTrue(!response.getContent().contains("Old API"));
    }

    @Test
    public void shouldReportVisualValidationIssues() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='80' as='geometry'/></mxCell>
                <mxCell id='3' value='Service' vertex='1' parent='1'><mxGeometry x='180' y='130' width='160' height='80' as='geometry'/></mxCell>
                <mxCell id='4' value='plain label' style='text;html=1;fillColor=#ffffff;' vertex='1' parent='1'><mxGeometry x='100' y='240' width='120' height='30' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioValidationResponse response = service.validateDiagram(request);

        assertEquals(false, response.isValid());
        assertEquals("major", response.getSeverity());
        assertTrue(response.getIssues().stream().anyMatch(issue -> issue.contains("Overlapping nodes: 2 and 3")));
        assertFalse("generic validation intentionally omits diagram-specific text styling rules",
                response.getIssues().stream().anyMatch(issue -> issue.contains("Text cell has opaque background: 4")));
    }

    @Test
    public void shouldDetectOverlaps() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='150' y='120' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.OverlapReportResponse response = service.detectOverlaps(request);

        assertEquals("overlap_report", response.getType());
        assertEquals(1, response.getOverlaps().size());
        assertEquals("2", response.getOverlaps().get(0).getSourceId());
        assertEquals("3", response.getOverlaps().get(0).getTargetId());
    }

    @Test
    public void shouldRouteEdgesWithOrthogonalStyleAndWaypoints() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='380' y='220' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        assertEquals("drawio_done", response.getType());
        assertTrue(response.getContent().contains("edgeStyle=orthogonalEdgeStyle"));
        assertTrue(response.getContent().contains("exitX=1"));
        assertTrue(response.getContent().contains("entryX=0"));
        assertTrue(response.getContent().contains("<Array as=\"points\">"));
    }

    @Test
    public void shouldRouteOppositeEdgesOnDistinctTracks() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend' vertex='1' parent='1'><mxGeometry x='380' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='1. request' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='5' value='2. return' style='dashed=1;' edge='1' parent='1' source='3' target='2'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        assertTrue(response.getContent().contains("exitY=0.3"));
        assertTrue(response.getContent().contains("entryY=0.3"));
        assertTrue(response.getContent().contains("exitY=0.7"));
        assertTrue(response.getContent().contains("entryY=0.7"));
        assertNoAnalyzerIssue(response.getContent(), CanvasIssueType.PARALLEL_EDGE_OVERLAP, List.of("4", "5"));
    }

    @Test
    public void shouldSpreadMultipleEdgesOnTheSameNodeSide() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='80' y='260' width='160' height='70' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend API' vertex='1' parent='1'><mxGeometry x='420' y='260' width='160' height='70' as='geometry'/></mxCell>
                <mxCell id='4' value='AI Service' vertex='1' parent='1'><mxGeometry x='780' y='120' width='180' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='History DB' vertex='1' parent='1'><mxGeometry x='780' y='440' width='180' height='80' as='geometry'/></mxCell>
                <mxCell id='6' value='2. call model' edge='1' parent='1' source='3' target='4'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='7' value='3. return XML' style='dashed=1;' edge='1' parent='1' source='4' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='8' value='4. save history' edge='1' parent='1' source='3' target='5'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='9' value='5. read history' style='dashed=1;' edge='1' parent='1' source='5' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        // Backend API's right side carries four connectors in this shape; each needs its own
        // y-track so arrow heads and source anchors do not collapse into two crowded points.
        List<Double> backendRightTracks = List.of(
                styleFraction(response.getContent(), "6", "exitY"),
                styleFraction(response.getContent(), "7", "entryY"),
                styleFraction(response.getContent(), "8", "exitY"),
                styleFraction(response.getContent(), "9", "entryY")
        );
        assertEquals(4, backendRightTracks.stream().distinct().count());
        for (Double track : backendRightTracks) {
            assertTrue("tracks should stay away from rounded-corner hit zones: " + backendRightTracks,
                    track >= 0.25D && track <= 0.75D);
        }
        for (int i = 0; i < backendRightTracks.size(); i++) {
            for (int j = i + 1; j < backendRightTracks.size(); j++) {
                assertTrue("tracks should have visible separation: " + backendRightTracks,
                        Math.abs(backendRightTracks.get(i) - backendRightTracks.get(j)) >= 0.12D);
            }
        }
    }

    @Test
    public void shouldAlignHorizontalLanesBetweenDifferentHeightNodes() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='100' y='100' width='220' height='140' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend API' vertex='1' parent='1'><mxGeometry x='560' y='130' width='180' height='90' as='geometry'/></mxCell>
                <mxCell id='4' value='1. request' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='5' value='2. return' style='dashed=1;' edge='1' parent='1' source='3' target='2'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        // Same numeric fractions do not mean the same pixel lane when node heights differ.
        // The router should pick one absolute lane per horizontal edge and derive both ports.
        assertEquals(
                100D + 140D * styleFraction(response.getContent(), "4", "exitY"),
                130D + 90D * styleFraction(response.getContent(), "4", "entryY"),
                0.1D
        );
        assertEquals(
                130D + 90D * styleFraction(response.getContent(), "5", "exitY"),
                100D + 140D * styleFraction(response.getContent(), "5", "entryY"),
                0.1D
        );
    }

    @Test
    public void shouldAlignVerticalLanesBetweenDifferentWidthNodes() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Client' vertex='1' parent='1'><mxGeometry x='100' y='100' width='240' height='100' as='geometry'/></mxCell>
                <mxCell id='3' value='Worker' vertex='1' parent='1'><mxGeometry x='140' y='420' width='120' height='90' as='geometry'/></mxCell>
                <mxCell id='4' value='1. submit' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='5' value='2. done' style='dashed=1;' edge='1' parent='1' source='3' target='2'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        // For vertical diagrams, matching fractions are not enough when node widths differ.
        // The router should derive exitX/entryX from one absolute x-lane per edge.
        assertEquals(
                100D + 240D * styleFraction(response.getContent(), "4", "exitX"),
                140D + 120D * styleFraction(response.getContent(), "4", "entryX"),
                0.1D
        );
        assertEquals(
                140D + 120D * styleFraction(response.getContent(), "5", "exitX"),
                100D + 240D * styleFraction(response.getContent(), "5", "entryX"),
                0.1D
        );
    }

    @Test
    public void shouldNotRerouteCornerAdjacentPortsBeforeTheMutationGate() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' style='rounded=1;' vertex='1' parent='1'><mxGeometry x='100' y='100' width='220' height='120' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend API' style='rounded=1;' vertex='1' parent='1'><mxGeometry x='560' y='100' width='180' height='120' as='geometry'/></mxCell>
                <mxCell id='4' value='request' style='endArrow=classic;edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.1;entryX=0;entryY=0.1;' edge='1' parent='1' source='2' target='3'>
                <mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        assertEquals(0.1D, styleFraction(response.getContent(), "4", "exitY"), 0.001D);
        assertEquals(0.1D, styleFraction(response.getContent(), "4", "entryY"), 0.001D);
    }

    @Test
    public void shouldNotRerouteVerticalCornerAdjacentPortsBeforeTheMutationGate() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Client' style='rounded=1;' vertex='1' parent='1'><mxGeometry x='100' y='100' width='240' height='100' as='geometry'/></mxCell>
                <mxCell id='3' value='Worker' style='rounded=1;' vertex='1' parent='1'><mxGeometry x='110' y='420' width='160' height='100' as='geometry'/></mxCell>
                <mxCell id='4' value='submit' style='endArrow=classic;edgeStyle=orthogonalEdgeStyle;exitX=0.1;exitY=1;entryX=0.1;entryY=0;' edge='1' parent='1' source='2' target='3'>
                <mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        assertEquals(0.1D, styleFraction(response.getContent(), "4", "exitX"), 0.001D);
        assertEquals(0.1D, styleFraction(response.getContent(), "4", "entryX"), 0.001D);
    }

    @Test
    public void shouldLogToolInvocationWithoutRawXml() {
        Logger logger = (Logger) LoggerFactory.getLogger(DrawioCanvasMcpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DrawioCanvasMcpService service = new DrawioCanvasMcpService();
            DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
            request.setReason("review requested route repair");
            request.setXml("""
                    <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                    <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                    <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='380' y='220' width='120' height='60' as='geometry'/></mxCell>
                    <mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                    </root></mxGraphModel>
                    """);

            service.routeEdges(request);

            assertTrue("route_edges should emit a compact diagnostic log",
                    appender.list.stream().anyMatch(event -> {
                        String message = event.getFormattedMessage();
                        return message.contains("[drawio-tool] name=route_edges")
                                && message.contains("xmlChars=")
                                && message.contains("reason=review requested route repair")
                                && !message.contains("<mxGraphModel");
                    }));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    public void shouldLogConsolidatedToolResultSummariesWithoutRawXml() {
        Logger logger = (Logger) LoggerFactory.getLogger(DrawioCanvasMcpService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DrawioCanvasMcpService service = new DrawioCanvasMcpService();
            DrawioCanvasMcpService.DrawioXmlRequest createRequest = new DrawioCanvasMcpService.DrawioXmlRequest();
            createRequest.setReason("initial draw");
            createRequest.setXml("<mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>");
            service.createDiagram(createRequest);

            DrawioCanvasMcpService.ModifyDiagramRequest modifyRequest = new DrawioCanvasMcpService.ModifyDiagramRequest();
            modifyRequest.setMode("replace_cells");
            modifyRequest.setReason("rename API node");
            modifyRequest.setTargetId("2");
            modifyRequest.setXml("""
                    <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                    <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                    </root></mxGraphModel>
                    """);
            modifyRequest.setCells("<mxCell id='2' value='Gateway' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>");
            service.modifyDiagram(modifyRequest);

            DrawioCanvasMcpService.OptimizeDiagramRequest optimizeRequest = new DrawioCanvasMcpService.OptimizeDiagramRequest();
            optimizeRequest.setReason("route layout");
            optimizeRequest.setXml("""
                    <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                    <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                    <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='380' y='220' width='120' height='60' as='geometry'/></mxCell>
                    <mxCell id='4' value='calls' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                    </root></mxGraphModel>
                    """);
            service.optimizeDiagram(optimizeRequest);

            DrawioCanvasMcpService.DrawioXmlRequest inspectRequest = new DrawioCanvasMcpService.DrawioXmlRequest();
            inspectRequest.setReason("post draw validation");
            inspectRequest.setXml("""
                    <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                    <mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                    <mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='150' y='120' width='120' height='60' as='geometry'/></mxCell>
                    </root></mxGraphModel>
                    """);
            service.inspectCanvas(inspectRequest);

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();

            assertTrue(messages.stream().anyMatch(message -> message.contains("[drawio-tool] name=create_diagram")
                    && message.contains("resultType=drawio_done")
                    && message.contains("inputXmlChars=")
                    && message.contains("outputXmlChars=")
                    && message.contains("reason=initial draw")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("[drawio-tool] name=modify_diagram")
                    && message.contains("requestedMode=replace_cells")
                    && message.contains("resolvedMode=replace_cells")
                    && message.contains("resultType=drawio_done")
                    && message.contains("targetId=2")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("[drawio-tool] name=optimize_diagram")
                    && message.contains("resultType=drawio_done")
                    && message.contains("inputXmlChars=")
                    && message.contains("outputXmlChars=")
                    && message.contains("reason=route layout")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("[drawio-tool] name=inspect_canvas")
                    && message.contains("resultType=canvas_inspection")
                    && message.contains("valid=false")
                    && message.contains("nodeCount=2")
                    && message.contains("edgeCount=0")
                    && message.contains("overlapCount=1")
                    && message.contains("issueCount=1")));
            assertTrue(messages.stream().noneMatch(message -> message.contains("<mxGraphModel")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    public void shouldMoveHorizontalEdgeLabelAwayFromBlockingNode() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend' vertex='1' parent='1'><mxGeometry x='500' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Audit Node' vertex='1' parent='1'><mxGeometry x='310' y='84' width='100' height='44' as='geometry'/></mxCell>
                <mxCell id='5' value='HTTPS request' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        Element geometry = edgeGeometry(response.getContent(), "5");
        assertTrue("label should be offset away from the edge line", Math.abs(Double.parseDouble(geometry.attributeValue("y"))) > 0);
        assertTrue("label should choose the lower side when the upper side overlaps another node",
                Double.parseDouble(geometry.attributeValue("y")) > 0);
    }

    @Test
    public void shouldLeaveEdgeLabelPlacementToAnExplicitRepairCandidate() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='60' y='100' width='160' height='100' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend' vertex='1' parent='1'><mxGeometry x='560' y='100' width='160' height='100' as='geometry'/></mxCell>
                <mxCell id='4' value='1. draw request' style='endArrow=classic;strokeWidth=5;edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='2' target='3'>
                <mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        Element geometry = edgeGeometry(response.getContent(), "4");
        assertEquals(null, geometry.attributeValue("y"));
    }

    @Test
    public void shouldRouteEdgeAroundBlockingNode() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        assertNoAnalyzerIssue(response.getContent(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
    }

    @Test
    public void shouldRouteContainerChildEdgeAroundBlockingChildNode() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='10' value='Container' style='swimlane;html=1;' vertex='1' parent='1'><mxGeometry x='100' y='100' width='400' height='250' as='geometry'/></mxCell>
                <mxCell id='11' value='Source' vertex='1' parent='10'><mxGeometry x='30' y='80' width='70' height='40' as='geometry'/></mxCell>
                <mxCell id='12' value='Target' vertex='1' parent='10'><mxGeometry x='300' y='80' width='70' height='40' as='geometry'/></mxCell>
                <mxCell id='13' value='Blocker' vertex='1' parent='10'><mxGeometry x='160' y='60' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='14' value='' edge='1' parent='10' source='11' target='12'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        assertNoAnalyzerIssue(response.getContent(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("14", "13"));
    }

    @Test
    public void shouldPreserveSafeExistingWaypoints() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='160' y='60'/><mxPoint x='320' y='60'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        assertNoAnalyzerIssue(response.getContent(), CanvasIssueType.EDGE_NODE_CROSSING, List.of("5", "4"));
        assertTrue(response.getContent().contains("x=\"160\" y=\"60\""));
        assertTrue(response.getContent().contains("x=\"320\" y=\"60\""));
    }

    @Test
    public void shouldPlaceBentEdgeLabelOnMiddleSegmentAwayFromNodes() throws Exception {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("""
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Frontend' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Backend' vertex='1' parent='1'><mxGeometry x='500' y='240' width='120' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocking Node' vertex='1' parent='1'><mxGeometry x='378' y='180' width='96' height='56' as='geometry'/></mxCell>
                <mxCell id='5' value='authorize' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """);

        DrawioCanvasMcpService.DrawioToolResponse response = service.routeEdges(request);

        Element geometry = edgeGeometry(response.getContent(), "5");
        double x = Double.parseDouble(geometry.attributeValue("x"));
        double y = Double.parseDouble(geometry.attributeValue("y"));
        assertTrue("label should be near the middle segment of the three-segment route", Math.abs(x) < 0.25D);
        assertTrue("label should move to the opposite side of the blocking node", y > 0);
    }

    @Test
    public void shouldBufferContinueDiagramUntilDone() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ContinueDiagramRequest first = new DrawioCanvasMcpService.ContinueDiagramRequest();
        first.setContinuationId("long-diagram");
        first.setXmlFragment("<mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>");
        first.setDone(false);

        DrawioCanvasMcpService.DrawioContinuationResponse partial = service.continueDiagram(first);

        assertEquals("continuation_result", partial.getType());
        assertEquals(false, partial.isComplete());
        assertTrue(partial.getBufferedLength() > 0);

        DrawioCanvasMcpService.ContinueDiagramRequest second = new DrawioCanvasMcpService.ContinueDiagramRequest();
        second.setContinuationId("long-diagram");
        second.setXmlFragment("<mxCell id='3' value='Service' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell>");
        second.setDone(true);

        DrawioCanvasMcpService.DrawioContinuationResponse done = service.continueDiagram(second);

        assertEquals("drawio_done", done.getType());
        assertEquals(true, done.isComplete());
        assertTrue(done.getContent().contains("<mxGraphModel>"));
        assertTrue(done.getContent().contains("value='API'"));
        assertTrue(done.getContent().contains("value='Service'"));
    }

    @Test
    public void shouldKeepContinuationBuffersIsolated() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ContinueDiagramRequest first = new DrawioCanvasMcpService.ContinueDiagramRequest();
        first.setContinuationId("a");
        first.setXmlFragment("<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='10' y='10' width='80' height='40' as='geometry'/></mxCell>");
        first.setDone(false);
        service.continueDiagram(first);

        DrawioCanvasMcpService.ContinueDiagramRequest second = new DrawioCanvasMcpService.ContinueDiagramRequest();
        second.setContinuationId("b");
        second.setXmlFragment("<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='20' y='20' width='80' height='40' as='geometry'/></mxCell>");
        second.setDone(true);

        DrawioCanvasMcpService.DrawioContinuationResponse done = service.continueDiagram(second);

        assertTrue(done.getContent().contains("value='B'"));
        assertTrue(!done.getContent().contains("value='A'"));
    }

    private Element edgeGeometry(String xml, String edgeId) throws Exception {
        Document document = DocumentHelper.parseText(xml);
        for (Object item : document.getRootElement().element("root").elements("mxCell")) {
            Element cell = (Element) item;
            if (edgeId.equals(cell.attributeValue("id"))) {
                return cell.element("mxGeometry");
            }
        }
        throw new AssertionError("Edge not found: " + edgeId);
    }

    private double styleFraction(String xml, String edgeId, String token) throws Exception {
        String style = edgeStyle(xml, edgeId);
        int start = style.indexOf(token + "=");
        if (start < 0) {
            throw new AssertionError("Missing style token " + token + " on edge " + edgeId + ": " + style);
        }
        start += token.length() + 1;
        int end = start;
        while (end < style.length() && (Character.isDigit(style.charAt(end)) || style.charAt(end) == '.')) {
            end++;
        }
        return Double.parseDouble(style.substring(start, end));
    }

    private String edgeStyle(String xml, String edgeId) throws Exception {
        Document document = DocumentHelper.parseText(xml);
        for (Object item : document.getRootElement().element("root").elements("mxCell")) {
            Element cell = (Element) item;
            if (edgeId.equals(cell.attributeValue("id"))) {
                return cell.attributeValue("style");
            }
        }
        throw new AssertionError("Edge not found: " + edgeId);
    }

    private void assertNoAnalyzerIssue(String xml, CanvasIssueType type, List<String> targetCellIds) {
        CanvasAnalysis analysis = new DefaultCanvasAnalyzer().analyze(xml, "architecture");
        assertFalse("Did not expect issue " + type + " with targets " + targetCellIds,
                analysis.getIssues().stream().anyMatch(issue ->
                        type == issue.getType() && issue.getTargetCellIds().equals(targetCellIds)));
    }

    private void assertAnalyzerIssue(String xml, CanvasIssueType type, List<String> targetCellIds) {
        CanvasAnalysis analysis = new DefaultCanvasAnalyzer().analyze(xml, "architecture");
        assertTrue("Expected issue " + type + " with targets " + targetCellIds,
                analysis.getIssues().stream().anyMatch(issue ->
                        type == issue.getType() && issue.getTargetCellIds().equals(targetCellIds)));
    }

    private void assertModifyRejected(DrawioCanvasMcpService.DrawioMutationResponse response) {
        assertEquals("tool_error", response.getType());
        assertEquals(null, response.getContent());
        assertEquals(null, response.getCells());
        assertEquals(null, response.getAnalysis());
        assertTrue(response.getMessage().contains("Use create_diagram"));
    }

    private void injectCanvasStateStore(DrawioCanvasMcpService service, ICanvasStateStore canvasStateStore) throws Exception {
        Field field = DrawioCanvasMcpService.class.getDeclaredField("canvasStateStore");
        field.setAccessible(true);
        field.set(service, canvasStateStore);
    }

    private void inject(DrawioCanvasMcpService service, String fieldName, Object value) throws Exception {
        Field field = DrawioCanvasMcpService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static class FixedCanvasStateStore implements ICanvasStateStore {
        private final String currentXml;

        private FixedCanvasStateStore(String currentXml) {
            this.currentXml = currentXml;
        }

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            return Optional.of(CanvasState.builder()
                    .userId(userId)
                    .diagramId(diagramId)
                    .currentXml(currentXml)
                    .version(3L)
                    .build());
        }

        @Override
        public CanvasState save(CanvasState state) {
            return state;
        }
    }

    private String edgeCrossingGraphXml() {
        return """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;
    }
}
