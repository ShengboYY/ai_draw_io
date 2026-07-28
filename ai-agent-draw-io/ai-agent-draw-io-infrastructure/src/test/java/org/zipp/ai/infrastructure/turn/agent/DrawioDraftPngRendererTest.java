package org.zipp.ai.infrastructure.turn.agent;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawioDraftPngRendererTest {

    @Test
    void rendersTheCommonPlainAgentCellSubsetAsHeadlessPng() {
        String xml = "<mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"a\" value=\"Start\" style=\"rounded=1;fillColor=#dbeafe\" "
                + "vertex=\"1\" parent=\"1\"><mxGeometry x=\"20\" y=\"20\" width=\"100\" "
                + "height=\"50\" as=\"geometry\"/></mxCell>"
                + "<mxCell id=\"b\" value=\"End\" style=\"ellipse;fillColor=#dcfce7\" "
                + "vertex=\"1\" parent=\"1\"><mxGeometry x=\"220\" y=\"20\" width=\"100\" "
                + "height=\"50\" as=\"geometry\"/></mxCell>"
                + "<mxCell id=\"e\" value=\"next\" edge=\"1\" source=\"a\" target=\"b\" "
                + "parent=\"1\"><mxGeometry relative=\"1\" as=\"geometry\"/></mxCell>"
                + "</root></mxGraphModel>";
        var analysis = new DefaultCanvasAnalyzer().analyze(xml, "flowchart");

        DrawioDraftPngRenderer.RenderedDraft rendered =
                new DrawioDraftPngRenderer().render(analysis);

        assertArrayEquals(
                new byte[]{(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47},
                java.util.Arrays.copyOf(rendered.png(), 4));
        assertTrue(rendered.width() > 128);
        assertTrue(rendered.height() >= 128);
        assertEquals(DrawioDraftPngRenderer.VERSION, rendered.rendererVersion());
    }
}
