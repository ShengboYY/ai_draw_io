package org.zipp.ai.infrastructure.turn.model;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEvidencePromptRendererTest {

    private static final String CANVAS_XML = """
            <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
            <mxCell id="node-1" value="Login" vertex="1" parent="1">
            <mxGeometry x="20" y="20" width="120" height="60" as="geometry"/>
            </mxCell></root></mxGraphModel>
            """;

    @Test
    void canvasSpecificSourceRequestReceivesExactCanvasState() {
        String prompt = new SourceEvidencePromptRenderer().render(
                context(), "prepared-1", "a".repeat(64), List.of(), true, true);

        assertTrue(prompt.contains(
                "nodeCount and edgeCount are authoritative server-derived facts"));
        assertTrue(prompt.contains("CURRENT_CANVAS_XML_DATA:"));
        assertTrue(prompt.contains(CANVAS_XML.trim()));
        assertTrue(prompt.indexOf("[Evidence Items]")
                < prompt.indexOf("CURRENT_CANVAS_XML_DATA:"));
    }

    @Test
    void documentOnlySourceRequestDoesNotReceiveCanvasXml() {
        String prompt = new SourceEvidencePromptRenderer().render(
                context(), "prepared-1", "a".repeat(64), List.of(), false, false);

        assertTrue(prompt.contains("CURRENT_CANVAS_DATA: OMITTED_NOT_REQUIRED"));
        assertFalse(prompt.contains(CANVAS_XML.trim()));
    }

    private BaseTurnContext context() {
        return new BaseTurnContext(
                new CurrentRequestContext(
                        "turn-1", "diagram-1", new CurrentInstruction("explain this diagram")),
                new AvailableContext<>(
                        new CurrentMessageAttachmentsContext("binding-1", List.of()),
                        "attachments"),
                new AbsentContext<>("no clarification"),
                new AvailableContext<>(
                        new TrustedCanvasContext(
                                1, 0, "one node", 2, "canvas-hash", CANVAS_XML),
                        "canvas"),
                new AvailableContext<>(new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(new ConversationContext(List.of(), ""), "conversation"),
                new AbsentContext<>("no membership"),
                new AbsentContext<>("no profile"),
                new AbsentContext<>("no memory"),
                new ContextDiagnostics(List.of()));
    }
}
