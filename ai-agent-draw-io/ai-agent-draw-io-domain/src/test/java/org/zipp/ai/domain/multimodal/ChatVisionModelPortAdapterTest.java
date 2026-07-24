package org.zipp.ai.domain.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatVisionModelPortAdapterTest {
    @Test
    void transportsPixelsInlineAndParsesTheExactObservationSchema() {
        FakeChat chat = new FakeChat("""
                {"observations":[{"evidenceId":"E1","kind":"ARROW","text":"A points to B",
                "bounds":{"x":0.1,"y":0.2,"width":0.5,"height":0.2},
                "direction":"LEFT_TO_RIGHT","confidence":0.94}],"gaps":[]}
                """);
        VisionModelPort adapter = new ChatVisionModelPortAdapter(chat, new ObjectMapper(), "agent-visual");

        VisionModelPort.Response response = adapter.observe(request());

        assertEquals(ObservationKind.ARROW, response.observations().get(0).kind());
        assertEquals(1, chat.lastCommand.getInlineDatas().size());
        assertTrue(chat.lastCommand.getTexts().get(0).getMessage().contains("untrusted data"));
    }

    @Test
    void rejectsUnexpectedFieldsInsteadOfLettingModelActionsEscapeTheSchema() {
        VisionModelPort adapter = new ChatVisionModelPortAdapter(new FakeChat(
                "{\"observations\":[],\"gaps\":[],\"drawioXml\":\"<xml/>\"}"),
                new ObjectMapper(), "agent-visual");

        assertThrows(IllegalArgumentException.class, () -> adapter.observe(request()));
    }

    @Test
    void rejectsNonTextualObservationFields() {
        VisionModelPort adapter = new ChatVisionModelPortAdapter(new FakeChat("""
                {"observations":[{"evidenceId":7,"kind":"ARROW","text":"A points to B",
                "bounds":{"x":0.1,"y":0.2,"width":0.5,"height":0.2},
                "direction":"LEFT_TO_RIGHT","confidence":0.94}],"gaps":[]}
                """), new ObjectMapper(), "agent-visual");

        assertThrows(IllegalArgumentException.class, () -> adapter.observe(request()));
    }

    @Test
    void refusesAnAgentThatCanExecuteTools() {
        FakeChat chat = new FakeChat("{\"observations\":[],\"gaps\":[]}") {
            @Override public boolean isAgentToolFree(String agentId) { return false; }
        };

        assertThrows(IllegalArgumentException.class,
                () -> new ChatVisionModelPortAdapter(chat, new ObjectMapper(), "agent-visual"));
    }

    @Test
    void parsesExplicitDiagramTopologyWithoutAcceptingXml() {
        FakeChat chat = new FakeChat("""
                {"diagramGraph":{"nodes":[
                  {"id":"a","label":"A","shape":"RECTANGLE",
                   "bounds":{"x":0.1,"y":0.2,"width":0.2,"height":0.1},
                   "groupId":null,"evidenceId":"E1","confidence":0.96},
                  {"id":"b","label":"B","shape":"ELLIPSE",
                   "bounds":{"x":0.6,"y":0.2,"width":0.2,"height":0.1},
                  "groupId":"","evidenceId":"E1","confidence":0.97}],
                  "edges":[{"id":"a-to-b","sourceId":"a","targetId":"b","label":"",
                   "direction":"FORWARD","lineStyle":"SOLID","waypoints":[],
                   "evidenceId":"E1","confidence":0.92}],
                  "groups":[],"unresolvedItems":[]},"gaps":[]}
                """);
        VisionModelPort adapter = new ChatVisionModelPortAdapter(chat, new ObjectMapper(), "agent-visual");

        VisionModelPort.Response response = adapter.observe(new VisionModelPort.Request(
                VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, "Reconstruct this diagram",
                List.of(new VisionModelPort.ImageInput("E1", "image/png", new byte[]{1})), 32));

        assertEquals("a", response.diagramGraph().nodes().get(0).id());
        assertEquals("b", response.diagramGraph().edges().get(0).targetId());
        assertTrue(chat.lastCommand.getTexts().get(0).getMessage().contains("Never return XML"));
    }

    @Test
    void repairsRecoverableDiagramBoundsThatExtendPastTheImageEdge() {
        FakeChat chat = new FakeChat("""
                {"diagramGraph":{"nodes":[
                  {"id":"resolution","label":"Record Resolution","shape":"ROUNDED_RECTANGLE",
                   "bounds":{"x":0.884,"y":0.039,"width":0.198,"height":0.163},
                   "groupId":null,"evidenceId":"E1","confidence":0.98}],
                  "edges":[],"groups":[],"unresolvedItems":[]},"gaps":[]}
                """);
        VisionModelPort adapter = new ChatVisionModelPortAdapter(chat, new ObjectMapper(), "agent-visual");

        VisionModelPort.Response response = adapter.observe(new VisionModelPort.Request(
                VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, "Reconstruct this diagram",
                List.of(new VisionModelPort.ImageInput("E1", "image/png", new byte[]{1})), 32));

        ObservationBounds bounds = response.diagramGraph().nodes().get(0).bounds();
        assertEquals(0.884, bounds.x(), 0.000001);
        assertEquals(0.116, bounds.width(), 0.000001);
    }

    @Test
    void rejectsGrosslyMalformedDiagramBoundsInsteadOfClippingThemIntoTheImage() {
        FakeChat chat = new FakeChat("""
                {"diagramGraph":{"nodes":[
                  {"id":"resolution","label":"Record Resolution","shape":"ROUNDED_RECTANGLE",
                   "bounds":{"x":-100,"y":0.039,"width":100.5,"height":0.163},
                   "groupId":null,"evidenceId":"E1","confidence":0.98}],
                  "edges":[],"groups":[],"unresolvedItems":[]},"gaps":[]}
                """);
        VisionModelPort adapter = new ChatVisionModelPortAdapter(chat, new ObjectMapper(), "agent-visual");

        assertThrows(IllegalArgumentException.class, () -> adapter.observe(
                new VisionModelPort.Request(
                        VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, "Reconstruct this diagram",
                        List.of(new VisionModelPort.ImageInput("E1", "image/png", new byte[]{1})), 32)));
    }

    private VisionModelPort.Request request() {
        return new VisionModelPort.Request(VisualObservationPurpose.FACT_VERIFICATION,
                "Where does the arrow point?",
                List.of(new VisionModelPort.ImageInput("E1", "image/png", new byte[]{1, 2, 3})), 8);
    }

    private static class FakeChat implements IChatService {
        private final String response;
        private ChatCommandEntity lastCommand;

        private FakeChat(String response) { this.response = response; }
        @Override public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() { return List.of(); }
        @Override public boolean isAgentToolFree(String agentId) { return true; }
        @Override public String createSession(String agentId, String userId) { return "session-1"; }
        @Override public String ensureSession(String agentId, String userId, String sessionId) { return sessionId; }
        @Override public List<String> handleMessage(String agentId, String userId, String message) { return List.of(response); }
        @Override public List<String> handleMessage(String agentId, String userId, String sessionId, String message) { return List.of(response); }
        @Override public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            return Flowable.empty();
        }
        @Override public List<String> handleMessage(ChatCommandEntity command) {
            lastCommand = command;
            return List.of(response);
        }
    }
}
