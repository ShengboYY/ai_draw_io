package org.zipp.ai.test.domain.agent.evaluation;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.evaluation.intake.ChatSemanticAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.intake.ISemanticAnomalyMiner;

import java.util.List;

import static org.junit.Assert.*;

public class ChatSemanticAnomalyMinerTest {

    @Test
    public void shouldParseOnlyTheFixedSchema() {
        RecordingChat chat = new RecordingChat("{\"isPotentialAnomaly\":true,\"confidence\":0.91,"
                + "\"failureFamily\":\"FALSE_SUCCESS\",\"evidence\":[\"assistant reported loading failure\"],"
                + "\"suggestedRisk\":\"high\",\"requiresHumanReview\":true}");
        ChatSemanticAnomalyMiner miner = new ChatSemanticAnomalyMiner(chat, "semantic-agent", "model-v1", "prompt-v1", "schema-v1");

        ISemanticAnomalyMiner.Finding finding = miner.analyze("{\"run\":{\"status\":\"SUCCESS\"}}");

        assertTrue(finding.isPotentialAnomaly());
        assertEquals("FALSE_SUCCESS", finding.failureFamily());
        assertTrue(miner.version().contains("model-v1"));
        assertTrue(chat.prompt.contains("sanitized_trace_projection"));
    }

    @Test
    public void shouldRejectUnknownFieldsAndLeakedProductionIdentifiers() {
        RecordingChat unknown = new RecordingChat("{\"isPotentialAnomaly\":false,\"confidence\":0.1,"
                + "\"failureFamily\":\"NONE\",\"evidence\":[],\"suggestedRisk\":\"low\","
                + "\"requiresHumanReview\":false,\"approval\":true}");
        RecordingChat leaked = new RecordingChat("{\"isPotentialAnomaly\":true,\"confidence\":0.9,"
                + "\"failureFamily\":\"FALSE_SUCCESS\",\"evidence\":[\"run aru_private_1 failed\"],"
                + "\"suggestedRisk\":\"high\",\"requiresHumanReview\":true}");

        assertThrows(IllegalArgumentException.class, () -> miner(unknown).analyze("{}"));
        assertThrows(IllegalArgumentException.class, () -> miner(leaked).analyze("{}"));
    }

    private ChatSemanticAnomalyMiner miner(RecordingChat chat) {
        return new ChatSemanticAnomalyMiner(chat, "semantic-agent", "model-v1", "prompt-v1", "schema-v1");
    }

    private static final class RecordingChat implements IChatService {
        private final String output;
        private String prompt;
        private RecordingChat(String output) { this.output = output; }
        @Override public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() { return List.of(); }
        @Override public String createSession(String agentId, String userId) { return "session"; }
        @Override public String ensureSession(String agentId, String userId, String sessionId) { return sessionId; }
        @Override public List<String> handleMessage(String agentId, String userId, String message) { return List.of(output); }
        @Override public List<String> handleMessage(String agentId, String userId, String sessionId, String message) { prompt = message; return List.of(output); }
        @Override public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) { return Flowable.empty(); }
        @Override public List<String> handleMessage(ChatCommandEntity chatCommandEntity) { return List.of(output); }
    }
}
