package org.zipp.ai.test.domain.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.intent.DefaultIntentRoutingService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DefaultIntentRoutingServiceTest {

    @Test
    public void shouldUseStructuredCanvasXmlForFastPatchRoute() throws Exception {
        IntentRoutingCommand command = IntentRoutingCommand.builder()
                .message("把 API 改成 Gateway")
                .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                .build();

        IntentRoutingResult result = tryFastPatchRoute(command);

        assertEquals("draw_action", result.getIntent());
        assertEquals("edit_existing", result.getDrawMode());
        assertEquals("none", result.getDiagramType());
        assertEquals("edit_existing", result.getTaskType());
    }

    @Test
    public void shouldNotUseFastPatchRouteForCreateRequestsWithExistingCanvas() throws Exception {
        IntentRoutingCommand command = IntentRoutingCommand.builder()
                .message("重新画一个用户登录流程图")
                .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                .build();

        assertNull(tryFastPatchRoute(command));
    }

    @Test
    public void shouldLogHighLevelFastPathRoutingDecision() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultIntentRoutingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            IntentRoutingCommand command = IntentRoutingCommand.builder()
                    .userId("anon_123e4567-e89b-42d3-a456-426614174000")
                    .message("把 API 改成 Gateway")
                    .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                    .build();

            new DefaultIntentRoutingService().route(command);

            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("[intent-route] source=fast_path"))
                    .filter(event -> event.getFormattedMessage().contains("userId=anon***00"))
                    .filter(event -> !event.getFormattedMessage().contains("123e4567-e89b-42d3-a456-426614174000"))
                    .filter(event -> event.getFormattedMessage().contains("drawMode=edit_existing"))
                    .filter(event -> event.getFormattedMessage().contains("taskType=edit_existing"))
                    .count());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void shouldDisableSemanticReviewForVisualOnlyLayoutRequests() throws Exception {
        IntentRoutingResult result = routeWithStubbedLlm(
                "线重叠了，整理一下",
                "{\"intent\":\"draw_action\",\"drawMode\":\"edit_existing\",\"diagramType\":\"architecture\",\"skillName\":\"none\","
                        + "\"taskType\":\"optimize_layout\",\"needsCanvasQuality\":true,\"needsSemanticReview\":true,"
                        + "\"answerMode\":\"none\",\"answer\":\"\",\"reason\":\"layout cleanup\"}");

        assertEquals("optimize_layout", result.getTaskType());
        assertTrue(result.getNeedsCanvasQuality());
        assertFalse(result.getNeedsSemanticReview());
    }

    @Test
    public void shouldInferDiagramTypeWhenRouterOmitsIt() throws Exception {
        IntentRoutingResult result = routeWithStubbedLlm(
                "请画一个 jvm 架构图",
                "{\"intent\":\"draw_action\",\"drawMode\":\"new_diagram\",\"diagramType\":\"\",\"skillName\":\"none\","
                        + "\"taskType\":\"create_new\",\"needsCanvasQuality\":false,\"needsSemanticReview\":false,"
                        + "\"answerMode\":\"none\",\"answer\":\"\",\"reason\":\"new diagram\"}");

        assertEquals("architecture", result.getDiagramType());
    }

    @Test
    public void shouldRespectRouterDiagramTypeWhenProvided() throws Exception {
        IntentRoutingResult result = routeWithStubbedLlm(
                "请画一个 jvm 架构图",
                "{\"intent\":\"draw_action\",\"drawMode\":\"new_diagram\",\"diagramType\":\"Flowchart\",\"skillName\":\"none\","
                        + "\"taskType\":\"create_new\",\"needsCanvasQuality\":false,\"needsSemanticReview\":false,"
                        + "\"answerMode\":\"none\",\"answer\":\"\",\"reason\":\"router chose flowchart\"}");

        assertEquals("flowchart", result.getDiagramType());
    }

    @Test
    public void shouldDisableSemanticReviewForComponentFrameRequests() throws Exception {
        IntentRoutingResult result = routeWithStubbedLlm(
                "给这些节点加 component 框",
                "{\"intent\":\"draw_action\",\"drawMode\":\"edit_existing\",\"diagramType\":\"architecture\",\"skillName\":\"none\","
                        + "\"taskType\":\"edit_existing\",\"needsCanvasQuality\":true,\"needsSemanticReview\":true,"
                        + "\"answerMode\":\"none\",\"answer\":\"\",\"reason\":\"add visual component frame\"}");

        assertEquals("edit_existing", result.getTaskType());
        assertTrue(result.getNeedsCanvasQuality());
        assertFalse(result.getNeedsSemanticReview());
    }

    @Test
    public void shouldKeepSemanticReviewForBusinessConceptChanges() throws Exception {
        IntentRoutingResult result = routeWithStubbedLlm(
                "新增支付服务并连接订单服务",
                "{\"intent\":\"draw_action\",\"drawMode\":\"edit_existing\",\"diagramType\":\"architecture\",\"skillName\":\"none\","
                        + "\"taskType\":\"edit_existing\",\"needsCanvasQuality\":true,\"needsSemanticReview\":true,"
                        + "\"answerMode\":\"none\",\"answer\":\"\",\"reason\":\"add business service and relationship\"}");

        assertTrue(result.getNeedsSemanticReview());
    }

    @Test
    public void shouldKeepSemanticReviewForProfessionalCorrectnessQuestions() throws Exception {
        IntentRoutingResult result = routeWithStubbedLlm(
                "这个架构专业上合理吗，有没有缺少认证模块",
                "{\"intent\":\"answer_only\",\"drawMode\":\"none\",\"diagramType\":\"architecture\",\"skillName\":\"none\","
                        + "\"taskType\":\"review_only\",\"needsCanvasQuality\":false,\"needsSemanticReview\":true,"
                        + "\"answerMode\":\"semantic_review\",\"answer\":\"\",\"reason\":\"professional correctness question\"}");

        assertTrue(result.getNeedsSemanticReview());
    }

    private IntentRoutingResult tryFastPatchRoute(IntentRoutingCommand command) throws Exception {
        // Keep the production method private while still locking the structured fast-path behavior.
        Method method = DefaultIntentRoutingService.class.getDeclaredMethod("tryFastPatchRoute", IntentRoutingCommand.class);
        method.setAccessible(true);
        return (IntentRoutingResult) method.invoke(new DefaultIntentRoutingService(), command);
    }

    private IntentRoutingResult routeWithStubbedLlm(String message, String llmJson) throws Exception {
        DefaultIntentRoutingService service = new DefaultIntentRoutingService();
        inject(service, "chatService", new StubChatService(List.of(llmJson)));
        inject(service, "skillCatalogService", new EmptySkillCatalogService());
        return service.route(IntentRoutingCommand.builder()
                .userId("alice")
                .message(message)
                .canvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                        + "<mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>")
                .build());
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class EmptySkillCatalogService extends SkillCatalogService {
        @Override
        public String catalogText(String ownerId) {
            return "";
        }
    }

    private static class StubChatService implements IChatService {

        private final List<String> outputs;

        private StubChatService(List<String> outputs) {
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

}
