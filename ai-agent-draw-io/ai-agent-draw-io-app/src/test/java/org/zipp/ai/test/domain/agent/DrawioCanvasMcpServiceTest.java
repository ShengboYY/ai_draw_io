package org.zipp.ai.test.domain.agent;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DrawioCanvasMcpServiceTest {

    @Test
    public void shouldReturnDrawioDoneForCellFragments() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml("<mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioToolResponse response = service.displayDiagram(request);

        assertEquals("drawio_done", response.getType());
        assertTrue(response.getContent().contains("<mxGraphModel>"));
        assertTrue(response.getContent().contains("<mxCell id=\"1\" parent=\"0\"/>"));
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
        assertTrue(response.getIssues().stream().anyMatch(issue -> issue.contains("Text cell has opaque background: 4")));
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
}
