package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.evaluation.IEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.visual.ChatVisualEvalJudge;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualEvalJudge;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.*;

public class ChatVisualEvalJudgeTest {
    @Test
    public void sendsRealInlinePixelsAndParsesFixedSchema() {
        ChatCommandEntity[] captured = new ChatCommandEntity[1];
        IChatService chat = proxy(IChatService.class, (method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1 && args[0] instanceof ChatCommandEntity command) {
                captured[0] = command;
                return List.of("{\"taskFulfilled\":true,\"readabilityScore\":4,\"layoutScore\":3,\"criticalIssues\":0,\"majorIssues\":0,\"evidence\":[\"labels are readable\"],\"recommendedHumanReview\":false}");
            }
            return defaultValue(method.getReturnType());
        });
        ChatVisualEvalJudge judge = new ChatVisualEvalJudge(chat, "visual-agent", "vlm-1", 0D);
        assertTrue(judge.version().contains("temperature=0.0"));
        IDiagramImageRenderer.RenderedDiagram before = image("before-pixels");
        IDiagramImageRenderer.RenderedDiagram after = image("after-pixels");

        EvalJudgeResult result = judge.judge(new IVisualEvalJudge.VisualJudgeInput("case-1", "flowchart", "improve layout",
                before, after, List.of("no deterministic overlap"), new IEvalJudge.EvaluatedAgentVersion("agent", 0D, "p", "r")));

        assertTrue(result.isAvailable()); assertTrue(result.isPassed());
        assertNotNull(captured[0]);
        assertEquals(2, captured[0].getInlineDatas().size());
        assertArrayEquals("before-pixels".getBytes(StandardCharsets.UTF_8), captured[0].getInlineDatas().get(0).getBytes());
        assertFalse(captured[0].getTexts().get(0).getMessage().contains("before-pixels"));
    }

    @Test
    public void textOnlyOrUnconfiguredProviderCannotProduceVisualScore() {
        int[] calls = new int[1];
        IChatService chat = proxy(IChatService.class, (method, args) -> { calls[0]++; return defaultValue(method.getReturnType()); });
        ChatVisualEvalJudge judge = new ChatVisualEvalJudge(chat, "visual-agent", "unconfigured", 0D);
        EvalJudgeResult result = judge.judge(new IVisualEvalJudge.VisualJudgeInput("case-1", "flowchart", "task",
                image("before"), image("after"), List.of(), null));
        assertFalse(result.isAvailable());
        assertEquals(0, calls[0]);
    }

    private IDiagramImageRenderer.RenderedDiagram image(String value) {
        return new IDiagramImageRenderer.RenderedDiagram(value.getBytes(StandardCharsets.UTF_8), "image/png", "fixture-v1", 10, 10);
    }

    private static Object defaultValue(Class<?> type) { if (!type.isPrimitive()) return null; if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return 0D; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation invocation) { return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (value, method, args) -> invocation.call(method, args == null ? new Object[0] : args)); }
    private interface Invocation { Object call(java.lang.reflect.Method method, Object[] args); }
}
