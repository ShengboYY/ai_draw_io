package org.zipp.ai.domain.agent.service.chat;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChatServiceDraftDiagramTest {

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
