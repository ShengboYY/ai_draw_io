package org.zipp.ai.test.domain.agent;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.quality.DiagramQualityReport;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IDiagramQualityInspector;
import org.zipp.ai.domain.agent.service.review.DefaultCanvasReviewService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.DrawioSkillAccessContext;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultCanvasReviewServiceTest {

    @Test
    public void shouldParseFinalSemanticReviewJsonAfterToolCallOutput() throws Exception {
        DefaultCanvasReviewService service = new DefaultCanvasReviewService();
        inject(service, "diagramQualityInspector", (IDiagramQualityInspector) (message, diagramType) -> qualityReport());
        inject(service, "chatService", new StubChatService(List.of(
                "FunctionCall{id=Optional[call_1], args=Optional[{command=drawio-architecture}], name=Optional[Skill]}",
                "{\"overallRisk\":\"low\",\"summary\":\"final semantic review\",\"issues\":\"none\",\"recommendations\":\"keep it\"}"
        )));

        CanvasReviewContext context = service.buildReviewContext(CanvasReviewCommand.builder()
                .userId("alice")
                .message("review this architecture diagram")
                .routingResult(routingResult())
                .build());

        assertEquals("low", context.getSemanticReview().getOverallRisk());
        assertEquals("final semantic review", context.getSemanticReview().getSummary());
        assertEquals("none", context.getSemanticReview().getIssues());
        assertEquals("keep it", context.getSemanticReview().getRecommendations());
    }

    @Test
    public void shouldBindSemanticReviewSkillsFromManualSelectionAndDiagramType() throws Exception {
        CapturingChatService chatService = new CapturingChatService(List.of(
                "{\"overallRisk\":\"low\",\"summary\":\"ok\",\"issues\":\"none\",\"recommendations\":\"none\"}"
        ));
        DefaultCanvasReviewService service = new DefaultCanvasReviewService();
        inject(service, "diagramQualityInspector", (IDiagramQualityInspector) (message, diagramType) -> qualityReport());
        inject(service, "chatService", chatService);

        CanvasReviewCommand command = CanvasReviewCommand.builder()
                .userId("alice")
                .message("review this architecture diagram")
                .routingResult(routingResult())
                .selectedSkillNames(List.of("custom-architecture"))
                .build();

        service.buildReviewContext(command);

        assertTrue(chatService.allowedSkillNames.contains(SkillCatalogService.SHARED_XML_GUIDE_SKILL));
        assertTrue(chatService.allowedSkillNames.contains(SkillCatalogService.SHARED_SKILL));
        assertTrue(chatService.allowedSkillNames.contains("drawio-architecture"));
        assertTrue(chatService.allowedSkillNames.contains("custom-architecture"));
    }

    private IntentRoutingResult routingResult() {
        IntentRoutingResult result = new IntentRoutingResult();
        result.setRouteType("review_only");
        result.setDiagramType("architecture");
        result.setSkillName("none");
        result.setNeedsCanvasQuality(false);
        result.setNeedsSemanticReview(true);
        result.setAnswerMode("semantic_review");
        result.setAnswer("");
        result.setReason("test");
        return result;
    }

    private DiagramQualityReport qualityReport() {
        return DiagramQualityReport.builder()
                .nodeCount(1)
                .edgeCount(0)
                .diagramType("architecture")
                .canvasSummary("One architecture node.")
                .overallRisk("low")
                .layoutIssues(List.of())
                .readabilityIssues(List.of())
                .edgeIssues(List.of())
                .semanticHints(List.of())
                .recommendations(List.of())
                .build();
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class StubChatService implements IChatService {

        private final List<String> outputs;

        protected StubChatService(List<String> outputs) {
            this.outputs = outputs;
        }

        @Override
        public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
            return List.of();
        }

        @Override
        public String createSession(String agentId, String userId) {
            return "session-1";
        }

        @Override
        public String ensureSession(String agentId, String userId, String sessionId) {
            return sessionId;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String message) {
            return outputs;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            return outputs;
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            return Flowable.empty();
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
            return outputs;
        }
    }

    private static class CapturingChatService extends StubChatService {
        private List<String> allowedSkillNames;

        private CapturingChatService(List<String> outputs) {
            super(outputs);
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            allowedSkillNames = DrawioSkillAccessContext.resolve(sessionId)
                    .map(access -> access.allowedSkillNames().stream().toList())
                    .orElse(List.of());
            return super.handleMessage(agentId, userId, sessionId, message);
        }
    }
}
