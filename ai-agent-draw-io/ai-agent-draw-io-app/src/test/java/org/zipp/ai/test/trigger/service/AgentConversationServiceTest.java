package org.zipp.ai.test.trigger.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaExceededException;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.trigger.http.service.DrawioPromptContextBuilder;
import org.zipp.ai.trigger.http.service.DrawioStreamResponseWriter;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;
import org.zipp.ai.trigger.http.service.SkillContentProvider;
import org.zipp.ai.types.enums.ResponseCode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentConversationServiceTest {

    @Test
    public void shouldClampFrontendReviewIterationSetting() throws Exception {
        AgentConversationService service = new AgentConversationService();

        assertEquals(1, normalizeMaxReviewIterations(service, null));
        assertEquals(0, normalizeMaxReviewIterations(service, -1));
        assertEquals(0, normalizeMaxReviewIterations(service, 0));
        assertEquals(2, normalizeMaxReviewIterations(service, 2));
        assertEquals(3, normalizeMaxReviewIterations(service, 9));
    }

    @Test
    public void shouldIncludeReviewBudgetAndHighLevelAllowedToolsInRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setDrawMode("edit_existing");
        routingResult.setTaskType("edit_existing");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage("update the API label");
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"api\" value=\"API\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"120\" y=\"80\" width=\"100\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell></root></mxGraphModel>");

        String routedMessage = buildRoutedMessage(service, requestDTO, routingResult, 1);

        assertTrue(routedMessage.contains("\"maxReviewIterations\":1"));
        assertTrue(routedMessage.contains("\"allowedTools\""));
        assertTrue(routedMessage.contains("modify_diagram"));
        assertFalse(routedMessage.contains("inspect_canvas"));
        assertFalse(routedMessage.contains("patch_existing"));
        assertFalse(routedMessage.contains("append_existing"));
        assertFalse(routedMessage.contains("fallback_full_xml"));
        assertFalse(routedMessage.contains("find_cells"));
        assertFalse(routedMessage.contains("update_cells"));
        assertFalse(routedMessage.contains("validate_diagram"));
        assertTrue(routedMessage.contains("[Context: Current Draw.io XML]"));
        assertTrue(routedMessage.contains("<mxGraphModel"));
        assertTrue(routedMessage.contains("value=\"API\""));
        assertFalse(routedMessage.contains("display_diagram\",\"append_diagram"));
    }

    @Test
    public void shouldPermitNonRedrawReviewStrategyToolsAfterCreateNewDraft() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        injectSkillContentProvider(service);
        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setDrawMode("new_diagram");
        routingResult.setTaskType("create_new");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage("draw a flowchart");

        String routedMessage = buildRoutedMessage(service, requestDTO, routingResult, 1);

        assertTrue(routedMessage.contains("\"allowedTools\""));
        assertTrue(routedMessage.contains("create_diagram"));
        assertTrue(routedMessage.contains("modify_diagram"));
        assertTrue(routedMessage.contains("optimize_diagram"));
        assertTrue(routedMessage.contains("\"reviewRepairTools\":[\"modify_diagram\",\"optimize_diagram\"]"));
        assertTrue(routedMessage.contains("Review repair turns may use only reviewRepairTools"));
    }

    @Test
    public void shouldLogDerivedToolGateForRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setDrawMode("edit_existing");
        routingResult.setTaskType("edit_existing");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setUserId("anon_123e4567-e89b-42d3-a456-426614174000");
        requestDTO.setMessage("update the API label");
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"api\" value=\"API\" vertex=\"1\" parent=\"1\"/>"
                + "</root></mxGraphModel>");

        Logger logger = (Logger) LoggerFactory.getLogger(AgentConversationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            buildRoutedMessage(service, requestDTO, routingResult, 0);

            assertTrue(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("[draw-route] userId=anon***00")
                            && !message.contains("123e4567-e89b-42d3-a456-426614174000")
                            && message.contains("taskType=edit_existing")
                            && message.contains("allowedTools=[modify_diagram]")
                            && message.contains("maxReviewIterations=0")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void shouldKeepFullXmlOutOfIntentMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage("把 API 改成 Gateway");
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>");
        requestDTO.setCanvasSummary("The canvas contains 1 node and 0 edges. Main labels: API.");

        String intentMessage = buildIntentMessage(service, requestDTO);

        assertTrue(intentMessage.contains("[User Request]\n把 API 改成 Gateway"));
        assertTrue(intentMessage.contains("[Canvas Summary]\nThe canvas contains 1 node and 0 edges. Main labels: API."));
        assertTrue(intentMessage.contains("hasCanvas=true"));
        assertFalse(intentMessage.contains("<mxGraphModel"));
        assertFalse(intentMessage.contains("value=\"API\""));
    }

    @Test
    public void shouldIncludeFullXmlInDrawingContextMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage("把 API 改成 Gateway");
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"API\" vertex=\"1\" parent=\"1\"/></root></mxGraphModel>");
        requestDTO.setCanvasSummary("The canvas contains 1 node and 0 edges. Main labels: API.");

        String drawingContext = buildDrawingContextMessage(service, requestDTO);

        assertTrue(drawingContext.contains("[Context: Current Draw.io XML]"));
        assertTrue(drawingContext.contains("<mxGraphModel"));
        assertTrue(drawingContext.contains("value=\"API\""));
        assertTrue(drawingContext.contains("[User Request]\n把 API 改成 Gateway"));
    }

    @Test
    public void shouldPreferStoredCanvasStateWhenDiagramIdIsPresent() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        injectCanvasStateStore(service, new FixedCanvasStateStore(storedCanvasXml()));

        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setDrawMode("edit_existing");
        routingResult.setTaskType("edit_existing");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setUserId("alice");
        requestDTO.setDiagramId("diagram-1");
        requestDTO.setMessage("把 API 改成 Gateway");
        requestDTO.setCanvasXml(requestCanvasXml());

        String routedMessage = buildRoutedMessage(service, requestDTO, routingResult, 1);

        assertTrue(routedMessage.contains("value=\"Stored API\""));
        assertFalse(routedMessage.contains("value=\"Request API\""));
    }

    @Test
    public void shouldFallbackToRequestCanvasXmlWhenStoredCanvasStateIsMissing() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        injectCanvasStateStore(service, new FixedCanvasStateStore(""));

        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setDrawMode("edit_existing");
        routingResult.setTaskType("edit_existing");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setUserId("alice");
        requestDTO.setDiagramId("missing-diagram");
        requestDTO.setMessage("把 API 改成 Gateway");
        requestDTO.setCanvasXml(requestCanvasXml());

        String routedMessage = buildRoutedMessage(service, requestDTO, routingResult, 1);

        assertTrue(routedMessage.contains("value=\"Request API\""));
    }

    @Test
    public void shouldRejectSixthAnonymousPlatformBlockingRequestBeforeModelWork() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        for (int i = 0; i < 5; i++) {
            service.chat(platformRequest());
        }

        try {
            service.chat(platformRequest());
        } catch (AnonymousDemoQuotaExceededException expected) {
            assertEquals(ResponseCode.DEMO_QUOTA_EXHAUSTED.getCode(), expected.getCode());
            assertEquals(5, intentRoutingService.calls);
            assertEquals(5, chatService.handleMessageCalls);
            return;
        }
        throw new AssertionError("expected anonymous demo quota denial");
    }

    @Test
    public void shouldAllowAnonymousCustomKeyRequestsAfterDemoQuotaIsExhausted() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        for (int i = 0; i < 6; i++) {
            ChatRequestDTO requestDTO = platformRequest();
            requestDTO.setCustomApiKey("sk-user-owned");
            service.chat(requestDTO);
        }

        assertEquals(6, intentRoutingService.calls);
        assertEquals(6, chatService.handleMessageCalls);
    }

    @Test
    public void shouldReturnTypedStreamErrorBeforeModelWorkWhenDemoQuotaIsExhausted() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        for (int i = 0; i < 5; i++) {
            service.stream(platformRequest(), new CapturingEmitter());
        }

        CapturingEmitter deniedEmitter = new CapturingEmitter();
        service.stream(platformRequest(), deniedEmitter);

        String output = String.join("\n", deniedEmitter.sent);
        assertTrue(output.contains("\"type\":\"error\""));
        assertTrue(output.contains(ResponseCode.DEMO_QUOTA_EXHAUSTED.getCode()));
        assertEquals(5, intentRoutingService.calls);
        assertEquals(5, chatService.handleMessageStreamCalls);
    }

    private int normalizeMaxReviewIterations(AgentConversationService service, Integer value) throws Exception {
        // Exercise the private normalization boundary without widening production API surface.
        Method method = AgentConversationService.class.getDeclaredMethod("normalizeMaxReviewIterations", Integer.class);
        method.setAccessible(true);
        return (int) method.invoke(service, value);
    }

    private String buildIntentMessage(AgentConversationService service, ChatRequestDTO requestDTO) throws Exception {
        Method method = AgentConversationService.class.getDeclaredMethod("buildIntentMessage", ChatRequestDTO.class);
        method.setAccessible(true);
        return (String) method.invoke(service, requestDTO);
    }

    private String buildDrawingContextMessage(AgentConversationService service, ChatRequestDTO requestDTO) throws Exception {
        Method method = AgentConversationService.class.getDeclaredMethod("buildDrawingContextMessage", ChatRequestDTO.class);
        method.setAccessible(true);
        return (String) method.invoke(service, requestDTO);
    }

    private String buildRoutedMessage(AgentConversationService service,
                                      ChatRequestDTO requestDTO,
                                      IntentRoutingResult routingResult,
                                      int maxReviewIterations) throws Exception {
        Method method = AgentConversationService.class.getDeclaredMethod(
                "buildRoutedMessage",
                ChatRequestDTO.class,
                IntentRoutingResult.class,
                org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext.class,
                int.class,
                String.class,
                java.util.List.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, requestDTO, routingResult, null, maxReviewIterations, requestDTO.getUserId(), null);
    }

    private void injectPromptContextBuilder(AgentConversationService service) throws Exception {
        // Keep the production field private while giving this service-level test the real context builder.
        Field field = AgentConversationService.class.getDeclaredField("promptContextBuilder");
        field.setAccessible(true);
        field.set(service, new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService()));
    }

    private void injectCanvasStateStore(AgentConversationService service, ICanvasStateStore canvasStateStore) throws Exception {
        Field field = AgentConversationService.class.getDeclaredField("canvasStateStore");
        field.setAccessible(true);
        field.set(service, canvasStateStore);
    }

    private void injectSkillContentProvider(AgentConversationService service) throws Exception {
        Field field = AgentConversationService.class.getDeclaredField("skillContentProvider");
        field.setAccessible(true);
        field.set(service, new EmptySkillContentProvider());
    }

    private AgentConversationService quotaAwareService() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        injectSkillContentProvider(service);
        injectField(service, "streamResponseWriter", new DrawioStreamResponseWriter(new DrawioToolCallRenderer()));
        injectField(service, "anonymousDemoQuotaService", new AnonymousDemoQuotaService());
        return service;
    }

    private ChatRequestDTO platformRequest() {
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId("300000");
        requestDTO.setUserId("anon_123e4567-e89b-42d3-a456-426614174000");
        requestDTO.setSessionId("session-1");
        requestDTO.setMessage("draw a flowchart");
        return requestDTO;
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String storedCanvasXml() {
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"stored\" value=\"Stored API\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"120\" y=\"80\" width=\"100\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell></root></mxGraphModel>";
    }

    private String requestCanvasXml() {
        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"request\" value=\"Request API\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"120\" y=\"80\" width=\"100\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell></root></mxGraphModel>";
    }

    private static class FixedCanvasStateStore implements ICanvasStateStore {

        private final String xml;

        private FixedCanvasStateStore(String xml) {
            this.xml = xml;
        }

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            if (null == xml || xml.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(CanvasState.builder()
                    .userId(userId)
                    .diagramId(diagramId)
                    .currentXml(xml)
                    .version(3L)
                    .build());
        }

        @Override
        public CanvasState save(CanvasState state) {
            return state;
        }
    }

    private static class EmptySkillContentProvider extends SkillContentProvider {
        @Override
        public String buildSkillSection(java.util.List<String> skillNames, String ownerId) {
            return "";
        }
    }

    private static class CountingIntentRoutingService implements IIntentRoutingService {
        private int calls;

        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            calls++;
            return IntentRoutingResult.fallbackDrawAction("test");
        }
    }

    private static class CountingChatService implements IChatService {
        private int handleMessageCalls;
        private int handleMessageStreamCalls;

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
            return sessionId == null || sessionId.isBlank() ? "session-1" : sessionId;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String message) {
            return handleMessage(agentId, userId, "session-1", message);
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            handleMessageCalls++;
            return List.of("{\"type\":\"user\",\"content\":\"ok\"}");
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            handleMessageStreamCalls++;
            return Flowable.empty();
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
            return List.of("{\"type\":\"user\",\"content\":\"ok\"}");
        }
    }

    private static class CapturingEmitter extends ResponseBodyEmitter {
        private final java.util.List<String> sent = new java.util.ArrayList<>();
        private boolean completed;

        @Override
        public void send(Object object) throws IOException {
            sent.add(String.valueOf(object));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

}
