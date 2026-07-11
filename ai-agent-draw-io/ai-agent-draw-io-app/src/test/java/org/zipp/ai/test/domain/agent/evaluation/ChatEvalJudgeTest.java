package org.zipp.ai.test.domain.agent.evaluation;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.evaluation.ChatEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.DrawioGraphNormalizer;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class ChatEvalJudgeTest {

    @Test
    public void shouldCallDedicatedJudgeAndParseTheStrictSchema() {
        RecordingChat chat = new RecordingChat("{\"task_fulfilled\":true,\"semantic_score\":4,\"visual_score\":5,"
                + "\"unexpected_side_effect\":false,\"severity\":\"none\",\"evidence\":[\"Gateway is connected\"],"
                + "\"recommended_human_review\":false}");
        ChatEvalJudge judge = new ChatEvalJudge(chat, "judge-agent");
        DrawioGraphNormalizer.Graph graph = new DrawioGraphNormalizer.Graph(
                Set.of("gateway"), Set.of(), Map.of("gateway", 1));

        IEvalJudge.JudgeInput input = input("architecture", graph, true);
        EvalJudgeResult result = judge.judge(input);

        assertTrue(result.isAvailable());
        assertTrue(result.isPassed());
        assertEquals(4.5D, result.getScore(), 0.001D);
        assertEquals(judge.version(input), result.getJudgeVersion());
        assertTrue(result.getJudgeVersion().contains("model=judge-model-v1"));
        assertTrue(chat.prompt.contains("final_graph"));
        assertTrue(chat.prompt.contains("deterministic_issues"));
        assertTrue(chat.prompt.contains("render_evidence"));
        assertFalse(chat.prompt.contains("<mxGraphModel"));
    }

    @Test
    public void invalidOrMarkdownWrappedOutputMustBeUnavailable() {
        RecordingChat chat = new RecordingChat("```json\n{\"task_fulfilled\":true}\n```");

        EvalJudgeResult result = new ChatEvalJudge(chat, "judge-agent").judge(
                input("flowchart", new DrawioGraphNormalizer.Graph(Set.of(), Set.of(), Map.of()), true));

        assertFalse(result.isAvailable());
        assertFalse(result.isPassed());
    }

    @Test
    public void diagramJudgeWithoutRenderedEvidenceMustBeUnavailableWithoutCallingTheModel() {
        RecordingChat chat = new RecordingChat("unused");

        EvalJudgeResult result = new ChatEvalJudge(chat, "judge-agent").judge(
                input("architecture", new DrawioGraphNormalizer.Graph(Set.of("gateway"), Set.of(), Map.of()), false));

        assertFalse(result.isAvailable());
        assertNull(chat.prompt);
        assertTrue(result.getEvidence().contains("judge_unavailable:visual_evidence_unavailable"));
    }

    private IEvalJudge.JudgeInput input(String diagramType, DrawioGraphNormalizer.Graph graph, boolean rendered) {
        IEvalJudge.RenderEvidence images = rendered ? new IEvalJudge.RenderEvidence("before-image", "after-image") : null;
        return new IEvalJudge.JudgeInput("case-1", diagramType, "Add a gateway", graph, graph, "Done",
                List.of("no deterministic issues"), List.of("modify_diagram:SUCCESS"),
                new IEvalJudge.EvidenceVersion("judge-model-v1", 0D, "renderer-v1", "architecture-rubric-v1"), images);
    }

    private static final class RecordingChat implements IChatService {
        private final String output;
        private String prompt;
        private RecordingChat(String output) { this.output = output; }
        @Override public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() { return List.of(); }
        @Override public String createSession(String agentId, String userId) { return "session"; }
        @Override public String ensureSession(String agentId, String userId, String sessionId) { return sessionId; }
        @Override public List<String> handleMessage(String agentId, String userId, String message) { return List.of(output); }
        @Override public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            this.prompt = message;
            return List.of(output);
        }
        @Override public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            return Flowable.empty();
        }
        @Override public List<String> handleMessage(ChatCommandEntity chatCommandEntity) { return List.of(output); }
    }
}
