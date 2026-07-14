package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.evaluation.visual.ChatVisualAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualAnomalyMiner;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ChatVisualAnomalyMinerTest {
    @Test
    public void sendsInlinePixelsAndAcceptsOnlyNonIdentifyingEvidenceCodes() {
        ChatCommandEntity[] captured = new ChatCommandEntity[1];
        IChatService chat = proxy((method, args) -> {
            if (method.getName().equals("createSession")) return "session";
            if (method.getName().equals("handleMessage") && args.length == 1) { captured[0] = (ChatCommandEntity) args[0]; return List.of("{\"potentialIssue\":true,\"confidence\":0.9,\"issueFamily\":\"READABILITY\",\"evidence\":[\"TEXT_TOO_SMALL\"],\"suggestedRisk\":\"high\",\"syntheticReconstructionSuggestion\":\"SYNTHETIC_SMALL_LABEL_FLOW\",\"requiresHumanReview\":true}"); }
            return null;
        });
        ChatVisualAnomalyMiner miner = new ChatVisualAnomalyMiner(chat, "miner", "vlm-1", 0D);
        assertTrue(miner.version().contains("temperature=0.0"));
        IVisualAnomalyMiner.Finding finding = miner.analyze(input());
        assertTrue(finding.potentialIssue()); assertArrayEquals("pixels".getBytes(StandardCharsets.UTF_8), captured[0].getInlineDatas().get(0).getBytes());
    }

    @Test
    public void rejectsFreeTextThatCouldCopyProductionLabels() {
        IChatService chat = proxy((method, args) -> method.getName().equals("createSession") ? "session"
                : method.getName().equals("handleMessage") ? List.of("{\"potentialIssue\":true,\"confidence\":0.9,\"issueFamily\":\"READABILITY\",\"evidence\":[\"Customer-42 label is tiny\"],\"suggestedRisk\":\"high\",\"syntheticReconstructionSuggestion\":\"SYNTHETIC_SMALL_LABEL_FLOW\",\"requiresHumanReview\":true}") : null);
        assertThrows(IllegalArgumentException.class, () -> new ChatVisualAnomalyMiner(chat, "miner", "vlm-1", 0D).analyze(input()));
    }

    private IVisualAnomalyMiner.Input input() { return new IVisualAnomalyMiner.Input(new IDiagramImageRenderer.RenderedDiagram("pixels".getBytes(StandardCharsets.UTF_8), "image/png", "fixture", 10, 10), List.of(), "flowchart"); }
    @SuppressWarnings("unchecked") private IChatService proxy(Invocation invocation) { return (IChatService) Proxy.newProxyInstance(IChatService.class.getClassLoader(), new Class<?>[]{IChatService.class}, (value, method, args) -> invocation.call(method, args == null ? new Object[0] : args)); }
    private interface Invocation { Object call(java.lang.reflect.Method method, Object[] args); }
}
