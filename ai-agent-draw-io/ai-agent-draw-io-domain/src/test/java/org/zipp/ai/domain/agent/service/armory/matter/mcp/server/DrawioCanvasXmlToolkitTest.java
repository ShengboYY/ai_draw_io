package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawioCanvasXmlToolkitTest {

    @Test
    void addsWrappingStyleToUnstyledVertexLabels() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="step1" value="Primary Responder hands an acknowledged incident to the Secondary Responder." vertex="1" parent="1">
                <mxGeometry x="40" y="40" width="260" height="70" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        String normalized = new DrawioCanvasXmlToolkit().toGraphModel(xml);

        // CanvasMutationGate persists this normalized XML to the user-visible Draw.io canvas.
        assertTrue(normalized.contains("style=\"whiteSpace=wrap;html=1;\""));
    }

    @Test
    void wrapsAnIndependentDevelopmentProbeLabel() {
        String xml = """
                <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                <mxCell id="escalation" value="Escalation Coordinator verifies the mitigation owner and publishes the recovery decision before the service review closes." vertex="1" parent="1">
                <mxGeometry x="80" y="60" width="260" height="70" as="geometry"/></mxCell>
                </root></mxGraphModel>
                """;

        String normalized = new DrawioCanvasXmlToolkit().toGraphModel(xml);

        assertTrue(normalized.contains("id=\"escalation\""));
        assertTrue(normalized.contains("style=\"whiteSpace=wrap;html=1;\""));
    }
}
