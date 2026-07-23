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
