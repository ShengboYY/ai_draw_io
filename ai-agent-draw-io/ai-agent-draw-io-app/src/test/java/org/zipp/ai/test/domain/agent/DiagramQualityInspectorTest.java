package org.zipp.ai.test.domain.agent;

import org.zipp.ai.domain.agent.model.valobj.canvas.DrawioCanvasSnapshot;
import org.zipp.ai.domain.agent.model.valobj.quality.DiagramQualityReport;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.domain.agent.service.quality.DefaultDiagramQualityInspector;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagramQualityInspectorTest {

    @Test
    public void shouldDetectOverlappingNodes() {
        DefaultDiagramQualityInspector inspector = new DefaultDiagramQualityInspector(new DefaultDrawioCanvasSnapshotService());
        String xml = "<mxGraphModel><root>"
                + "<mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='A' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='80' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='B' vertex='1' parent='1'><mxGeometry x='150' y='120' width='120' height='80' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";

        DiagramQualityReport report = inspector.inspect(xml, "architecture");

        assertEquals(2, report.getNodeCount());
        assertFalse(report.getLayoutIssues().isEmpty());
        assertEquals("node_overlap", report.getLayoutIssues().get(0).getType());
    }

    @Test
    public void shouldDetectOpaqueStandaloneText() {
        DefaultDiagramQualityInspector inspector = new DefaultDiagramQualityInspector(new DefaultDrawioCanvasSnapshotService());
        String xml = "<mxGraphModel><root>"
                + "<mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='Section Label' style='text;html=1;fillColor=#ffffff;strokeColor=#000000;' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='120' height='30' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";

        DiagramQualityReport report = inspector.inspect(xml, "architecture");

        assertFalse(report.getReadabilityIssues().isEmpty());
        assertEquals("opaque_text_background", report.getReadabilityIssues().get(0).getType());
    }

    @Test
    public void shouldBuildCanvasSnapshot() {
        DefaultDrawioCanvasSnapshotService snapshotService = new DefaultDrawioCanvasSnapshotService();
        String xml = "<mxGraphModel><root>"
                + "<mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='User' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='Order' vertex='1' parent='1'><mxGeometry x='300' y='100' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='places' edge='1' source='2' target='3' parent='1'><mxGeometry relative='1' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";

        DrawioCanvasSnapshot snapshot = snapshotService.fromXml(xml, "uml_class");

        assertTrue(snapshot.isValid());
        assertEquals(2, snapshot.nodeCount());
        assertEquals(1, snapshot.edgeCount());
        assertTrue(snapshot.getSummary().contains("2 nodes"));
    }

    @Test
    public void shouldDetectWeakArchitectureBoundary() {
        DefaultDiagramQualityInspector inspector = new DefaultDiagramQualityInspector(new DefaultDrawioCanvasSnapshotService());
        String xml = "<mxGraphModel><root>"
                + "<mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='Client' vertex='1' parent='1'><mxGeometry x='100' y='100' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='API' vertex='1' parent='1'><mxGeometry x='250' y='130' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='Service' vertex='1' parent='1'><mxGeometry x='400' y='170' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='5' value='DB' vertex='1' parent='1'><mxGeometry x='550' y='210' width='100' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='6' value='Cache' vertex='1' parent='1'><mxGeometry x='700' y='250' width='100' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";

        DiagramQualityReport report = inspector.inspect(xml, "architecture");

        assertTrue(report.getCanvasSummary().contains("5 nodes"));
        assertFalse(report.getSemanticHints().isEmpty());
    }

}
