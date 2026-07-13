package org.zipp.ai.domain.agent.service.chat;

import com.alibaba.fastjson.JSONObject;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatServiceDraftDiagramTest {

    @Test
    public void toolErrorIsProjectedAsFailedEvaluationEvidence() {
        JSONObject rejected = new JSONObject();
        rejected.put("type", "tool_error");

        assertEquals("FAILED", ChatService.observedToolStatus(rejected));
        assertEquals("SUCCESS", ChatService.observedToolStatus(new JSONObject()));
    }

    @Test
    public void shouldExtractDraftDiagramFromDrawioMutationToolResponse() {
        String xml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
        Event event = Event.builder()
                .author("agent_drawer")
                .content(Content.fromParts(Part.fromFunctionResponse("create_diagram", Map.of(
                        "type", "drawio_done",
                        "content", xml
                ))))
                .build();

        Optional<String> draftDiagram = ChatService.extractDraftDiagram(event);

        assertTrue(draftDiagram.isPresent());
        assertEquals(xml, draftDiagram.get());
    }

    @Test
    public void shouldExtractDraftDiagramFromDrawioDoneText() {
        String xml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
        Event event = Event.builder()
                .author("agent_drawer")
                .content(Content.fromParts(Part.fromText("{\"type\":\"drawio_done\",\"content\":\"" + xml + "\"}")))
                .build();

        Optional<String> draftDiagram = ChatService.extractDraftDiagram(event);

        assertTrue(draftDiagram.isPresent());
        assertEquals(xml, draftDiagram.get());
    }

    @Test
    public void shouldMergePatchCellsIntoCurrentDraftDiagram() {
        String currentXml = """
                <mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>
                <mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>
                </root></mxGraphModel>
                """;
        Event event = Event.builder()
                .author("agent_repair_drawer")
                .content(Content.fromParts(Part.fromFunctionResponse("modify_diagram", Map.of(
                        "type", "patch_cells",
                        "cells", "<mxCell id='2' value='API v2' vertex='1' parent='1'><mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"
                ))))
                .build();

        Optional<String> draftDiagram = ChatService.extractDraftDiagram(event, currentXml);

        assertTrue(draftDiagram.isPresent());
        assertTrue(draftDiagram.get().contains("API v2"));
        assertFalse(draftDiagram.get().contains("value='API'"));
    }

    @Test
    public void shouldIgnoreNonDrawingToolResponses() {
        Event event = Event.builder()
                .author("agent_drawer")
                .content(Content.fromParts(Part.fromFunctionResponse("validate_diagram", Map.of(
                        "type", "validation_result",
                        "content", "ok"
                ))))
                .build();

        assertTrue(ChatService.extractDraftDiagram(event).isEmpty());
    }
}
