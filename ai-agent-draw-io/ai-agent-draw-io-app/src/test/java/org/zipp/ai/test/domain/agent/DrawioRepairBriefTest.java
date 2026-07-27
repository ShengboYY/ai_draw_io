package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Phase 2 drawing loop: every mutation tool response must tell the drawer what to do next.
 * A clean canvas yields an explicit finish signal; blocking issues yield numbered,
 * executable repair directives that never suggest redrawing.
 */
public class DrawioRepairBriefTest {

    private static final String CLEAN_FRAGMENTS =
            "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='160' height='60' as='geometry'/></mxCell>"
                    + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='400' y='40' width='160' height='60' as='geometry'/></mxCell>"
                    + "<mxCell id='4' value='link' style='endArrow=classic;html=1;' edge='1' parent='1' source='2' target='3'>"
                    + "<mxGeometry relative='1' as='geometry'/></mxCell>";

    private static final String DUPLICATE_ID_FRAGMENTS =
            "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='160' height='60' as='geometry'/></mxCell>"
                    + "<mxCell id='2' value='B' vertex='1' parent='1'><mxGeometry x='400' y='40' width='160' height='60' as='geometry'/></mxCell>";

    private static final String OVERLAPPING_FRAGMENTS =
            "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='40' y='40' width='160' height='60' as='geometry'/></mxCell>"
                    + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='80' y='60' width='160' height='60' as='geometry'/></mxCell>";

    @Test
    public void cleanDiagramGetsFinishSignal() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml(CLEAN_FRAGMENTS);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        assertNotNull(response.getRepairBrief());
        assertTrue(response.getRepairBrief().startsWith("APPLIED. No blocking issues"));
        assertTrue(response.getRepairBrief().contains("finish"));
    }

    @Test
    public void blockingIssuesGetNumberedRepairDirectives() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
        request.setXml(DUPLICATE_ID_FRAGMENTS);

        DrawioCanvasMcpService.DrawioToolResponse response = service.createDiagram(request);

        String brief = response.getRepairBrief();
        assertNotNull(brief);
        assertTrue(brief.contains("blocking issue(s) remain"));
        assertTrue(brief.contains("1. [critical]"));
        assertTrue(brief.contains("assign cell 2 a unique id"));
        assertTrue(brief.contains("modify_diagram"));
        assertTrue(brief.contains("never redraw"));
        assertFalse(brief.contains("create_diagram"));
    }

    @Test
    public void patchWithoutBaseCanvasGetsFinishSignalNotStale() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("patch");
        request.setCells("<mxCell id='2' value='Renamed' vertex='1' parent='1'><mxGeometry x='40' y='40' width='160' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertNotNull(response.getRepairBrief());
        assertTrue(response.getRepairBrief().startsWith("APPLIED. No blocking issues"));
    }

    @Test
    public void patchWithBaseCanvasKeepsVisualIssuesOutOfDeterministicRepair() {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("patch");
        request.setXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"2\" value=\"A\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"40\" y=\"40\" width=\"160\" height=\"60\" as=\"geometry\"/></mxCell>"
                + "<mxCell id=\"3\" value=\"B\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"400\" y=\"40\" width=\"160\" height=\"60\" as=\"geometry\"/></mxCell>"
                + "</root></mxGraphModel>");
        // Visual overlap remains analysis evidence; only structural failures trigger self-repair.
        request.setCells("<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='60' y='50' width='160' height='60' as='geometry'/></mxCell>");

        DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);

        assertNotNull(response.getRepairBrief());
        assertTrue(response.getRepairBrief().startsWith("APPLIED. No blocking issues"));
        assertNotNull(response.getAnalysis());
        assertTrue(response.getAnalysis().getIssues().stream()
                .anyMatch(issue -> "NODE_OVERLAP".equals(issue.getType())));
    }
}
