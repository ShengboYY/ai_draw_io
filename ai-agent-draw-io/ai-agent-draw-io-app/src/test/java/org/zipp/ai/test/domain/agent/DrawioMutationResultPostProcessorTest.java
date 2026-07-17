package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioMutationResultPostProcessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void visualMajorIssueIsReportedWithoutTriggeringDeterministicSelfRepair() {
        String candidate = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='First' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='80' as='geometry'/></mxCell>
                <mxCell id='3' value='Second' vertex='1' parent='1'><mxGeometry x='140' y='120' width='160' height='80' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;

        Map<String, Object> processed = new DrawioMutationResultPostProcessor().process(
                "create_diagram",
                Map.of("xml", candidate),
                Map.of("type", "drawio", "content", candidate),
                new HashMap<>());

        assertTrue(String.valueOf(processed.get("repairBrief"))
                .startsWith("APPLIED. No blocking issues"));
    }

    @Test
    public void structuralCriticalIssueStillTriggersDeterministicSelfRepair() {
        String candidate = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='First' vertex='1' parent='1'><mxGeometry x='100' y='100' width='160' height='80' as='geometry'/></mxCell>
                <mxCell id='2' value='Duplicate' vertex='1' parent='1'><mxGeometry x='360' y='100' width='160' height='80' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;

        Map<String, Object> processed = new DrawioMutationResultPostProcessor().process(
                "create_diagram",
                Map.of("xml", candidate),
                Map.of("type", "drawio", "content", candidate),
                new HashMap<>());

        String repairBrief = String.valueOf(processed.get("repairBrief"));
        assertTrue(repairBrief.contains("[critical] Duplicate cell id: 2"));
        assertTrue(repairBrief.contains("If repair budget remains"));
    }

    @Test
    public void scopedRouteOnlyPreservesManualWaypointsThroughPostProcessing() {
        String currentXml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='7' value='Retry source' vertex='1' parent='1'><mxGeometry x='360' y='360' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='8' value='Retry target' vertex='1' parent='1'><mxGeometry x='40' y='360' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='6' value='' style='edgeStyle=orthogonalEdgeStyle;exitX=0;exitY=0.5;entryX=1;entryY=0.5;' edge='1' parent='1' source='7' target='8'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='320' y='500'/><mxPoint x='160' y='500'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """;
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(currentXml);
        request.setTargetEdgeIds(List.of("5"));

        DrawioCanvasMcpService.DrawioMutationResponse toolResponse =
                new DrawioCanvasMcpService().optimizeDiagram(request);
        Map<String, Object> state = new HashMap<>();
        state.put(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentXml);
        Map<String, Object> response = new HashMap<>();
        response.put("type", toolResponse.getType());
        response.put("cells", toolResponse.getCells());

        new DrawioMutationResultPostProcessor().process(
                "optimize_diagram",
                Map.of("mode", "route_only", "targetEdgeIds", List.of("5")),
                response,
                state);

        String canonical = String.valueOf(state.get(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY));
        assertEquals("patch_cells", toolResponse.getType());
        assertFalse(toolResponse.getAnalysis().getIssues().stream()
                .anyMatch(issue -> "EDGE_NODE_CROSSING".equals(issue.getType())));
        assertTrue(toolResponse.getCells().contains("id=\"5\""));
        assertFalse(toolResponse.getCells().contains("id=\"6\""));
        assertTrue(canonical.contains("style=\"edgeStyle=orthogonalEdgeStyle;exitX=0;exitY=0.5;entryX=1;entryY=0.5;\""));
        assertTrue(canonical.contains("<mxPoint x=\"320\" y=\"500\"/>"));
        assertTrue(canonical.contains("<mxPoint x=\"160\" y=\"500\"/>"));
    }

    @Test
    public void routeOnlyPostProcessingDoesNotRepairAnUnrelatedProblemEdge() {
        String currentXml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='Source' vertex='1' parent='1'><mxGeometry x='40' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='3' value='Target' vertex='1' parent='1'><mxGeometry x='360' y='120' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='4' value='Top blocker' vertex='1' parent='1'><mxGeometry x='210' y='110' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='5' value='' edge='1' parent='1' source='2' target='3'><mxGeometry relative='1' as='geometry'/></mxCell>
                <mxCell id='7' value='Retry source' vertex='1' parent='1'><mxGeometry x='40' y='360' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='8' value='Retry target' vertex='1' parent='1'><mxGeometry x='360' y='360' width='80' height='60' as='geometry'/></mxCell>
                <mxCell id='9' value='Bottom blocker' vertex='1' parent='1'><mxGeometry x='210' y='350' width='80' height='80' as='geometry'/></mxCell>
                <mxCell id='6' value='manual return' style='edgeStyle=orthogonalEdgeStyle;exitX=1;exitY=0.5;entryX=0;entryY=0.5;' edge='1' parent='1' source='7' target='8'>
                    <mxGeometry relative='1' as='geometry'><Array as='points'><mxPoint x='160' y='390'/><mxPoint x='320' y='390'/></Array></mxGeometry>
                </mxCell>
                </root></mxGraphModel>
                """;
        DrawioCanvasMcpService.OptimizeDiagramRequest request = new DrawioCanvasMcpService.OptimizeDiagramRequest();
        request.setMode("route_only");
        request.setXml(currentXml);
        request.setTargetEdgeIds(List.of("5"));
        DrawioCanvasXmlToolkit toolkit = new DrawioCanvasXmlToolkit();
        assertTrue("the regression needs an unrelated edge that the validator wants to reroute",
                new DefaultCanvasAnalyzer().analyze(currentXml, "flowchart").getIssues().stream()
                        .anyMatch(issue -> issue.getTargetCellIds().contains("6")
                                && "auto_reroute".equals(issue.getRepairability())));
        String unrelatedEdgeBefore = toolkit.edgeCells(currentXml, Set.of("6"));
        DrawioCanvasMcpService.DrawioMutationResponse toolResponse =
                new DrawioCanvasMcpService().optimizeDiagram(request);
        Map<String, Object> state = new HashMap<>();
        state.put(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentXml);
        Map<String, Object> response = new HashMap<>();
        response.put("type", toolResponse.getType());
        response.put("cells", toolResponse.getCells());

        new DrawioMutationResultPostProcessor().process(
                "optimize_diagram",
                Map.of("mode", "route_only", "targetEdgeIds", List.of("5")),
                response,
                state);

        String canonical = String.valueOf(state.get(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY));
        assertEquals("route_only must preserve a non-target edge even when it has its own issue",
                unrelatedEdgeBefore, toolkit.edgeCells(canonical, Set.of("6")));
    }

    @Test
    public void noSafeCandidateDoesNotMutateTheWorkingDraftOrLoseItsReason() {
        String currentXml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
        Map<String, Object> state = new HashMap<>();
        state.put(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentXml);
        Map<String, Object> response = new HashMap<>();
        response.put("type", "no_safe_candidate");
        response.put("repairBrief", "NO_SAFE_CANDIDATE: self-loop routing is not supported.");

        DrawioMutationResultPostProcessor.ProcessResult result =
                new DrawioMutationResultPostProcessor().processWithStatus(
                        "optimize_diagram", Map.of("mode", "route_only"), response, state);

        assertFalse(result.mutationApplied());
        assertEquals(currentXml, state.get(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY));
        assertEquals(response.get("repairBrief"), result.response().get("repairBrief"));
    }
}
