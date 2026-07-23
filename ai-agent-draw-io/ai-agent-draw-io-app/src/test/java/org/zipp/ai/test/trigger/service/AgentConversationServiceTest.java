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
import org.zipp.ai.domain.account.model.valobj.CreateModelCredentialCommand;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSecret;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSummary;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaExceededException;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.IModelCredentialService;
import org.zipp.ai.domain.account.service.PlatformDailyQuotaExceededException;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.DrawerContinuationContext;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.trigger.http.service.CanvasReviewImageValidator;
import org.zipp.ai.trigger.http.service.DrawioPromptContextBuilder;
import org.zipp.ai.trigger.http.service.DrawioStreamResponseWriter;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;
import org.zipp.ai.trigger.http.service.SkillContentProvider;
import org.zipp.ai.trigger.http.service.VisualReviewRolloutPolicy;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AgentConversationServiceTest {

    private static final String VALID_PNG_DATA_URL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";

    @Test
    public void shouldUseProductionVlmForReviewOnlyWithoutCallingTheLegacyReviewer() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        AtomicInteger visualReviewCalls = new AtomicInteger();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new ReviewOnlyRoutingService());
        injectField(service, "canvasAnalyzer", passingAnalyzer());
        injectField(service, "canvasReviewImageValidator", new CanvasReviewImageValidator());
        injectField(service, "canvasVisualReviewer", (ICanvasVisualReviewer) command -> {
            visualReviewCalls.incrementAndGet();
            assertEquals("CURRENT_CANVAS", command.getStage().name());
            assertEquals(VALID_PNG_DATA_URL, command.getAfterImageDataUrl());
            return CanvasVisualReviewResult.builder().available(true).summary("The canvas is readable.")
                    .issues(List.of()).recommendedHumanReview(false).build();
        });
        ChatRequestDTO request = platformRequest();
        request.setMessage("review this diagram");
        request.setCanvasXml(storedCanvasXml());
        request.setCanvasImageDataUrl(VALID_PNG_DATA_URL);
        request.setCanvasImageRendererVersion("drawio-embed-png-v1");
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(1, visualReviewCalls.get());
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
        assertTrue(output.contains("\"type\":\"review_started\""));
        assertTrue(output.contains("\"type\":\"review_result\""));
        assertTrue(output.contains("\"decision\":\"APPROVE\""));
        assertFalse(output.contains("\"type\":\"drawio_done\""));
    }

    @Test
    public void shouldReturnUnavailableWhenReviewOnlyScreenshotIsMissing() throws Exception {
        AgentConversationService service = quotaAwareService();
        AtomicInteger visualReviewCalls = new AtomicInteger();
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new ReviewOnlyRoutingService());
        injectField(service, "canvasAnalyzer", passingAnalyzer());
        injectField(service, "canvasReviewImageValidator", new CanvasReviewImageValidator());
        injectField(service, "canvasVisualReviewer", (ICanvasVisualReviewer) command -> {
            visualReviewCalls.incrementAndGet();
            return CanvasVisualReviewResult.unavailable("unexpected");
        });
        ChatRequestDTO request = platformRequest();
        request.setMessage("review this diagram");
        request.setCanvasXml(storedCanvasXml());
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(0, visualReviewCalls.get());
        assertTrue(output.contains("\"type\":\"review_result\""));
        assertTrue(output.contains("\"decision\":\"UNAVAILABLE\""));
    }

    @Test
    public void shouldNotCallProductionVlmWhenVisualReviewIsDisabled() throws Exception {
        AgentConversationService service = quotaAwareService();
        AtomicInteger visualReviewCalls = new AtomicInteger();
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new ReviewOnlyRoutingService());
        injectField(service, "canvasAnalyzer", passingAnalyzer());
        injectField(service, "canvasReviewImageValidator", new CanvasReviewImageValidator());
        injectField(service, "visualReviewRolloutPolicy", new VisualReviewRolloutPolicy(false, false));
        injectField(service, "canvasVisualReviewer", (ICanvasVisualReviewer) command -> {
            visualReviewCalls.incrementAndGet();
            return CanvasVisualReviewResult.unavailable("unexpected");
        });
        ChatRequestDTO request = platformRequest();
        request.setMessage("review this diagram");
        request.setCanvasXml(storedCanvasXml());
        request.setCanvasImageDataUrl(VALID_PNG_DATA_URL);
        request.setCanvasImageRendererVersion("drawio-embed-png-v1");
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(0, visualReviewCalls.get());
        assertTrue(output.contains("\"decision\":\"UNAVAILABLE\""));
    }

    @Test
    public void shouldAnswerWithoutReviewingWhenThereIsNoDrawableCanvas() throws Exception {
        AgentConversationService service = quotaAwareService();
        AtomicInteger visualReviewCalls = new AtomicInteger();
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new ReviewOnlyRoutingService());
        injectField(service, "canvasVisualReviewer", (ICanvasVisualReviewer) command -> {
            visualReviewCalls.incrementAndGet();
            return CanvasVisualReviewResult.unavailable("unexpected");
        });
        ChatRequestDTO request = platformRequest();
        request.setMessage("review this diagram");
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(0, visualReviewCalls.get());
        assertTrue(output.contains("There is no drawable canvas to review."));
        assertFalse(output.contains("\"type\":\"review_result\""));
    }

    private org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer passingAnalyzer() {
        return (xml, diagramType) -> CanvasAnalysis.builder()
                .valid(true).severity("ok").issues(List.of()).cells(List.of())
                .summary(CanvasSummaryData.builder().nodeCount(1).edgeCount(0).summary("one readable node").build())
                .build();
    }

    @Test
    public void shouldBypassIntentRoutingWhenContinuingTheDrawer() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService routingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", routingService);
        ChatRequestDTO request = platformRequest();
        request.setDiagramId("diagram-1");
        request.setCanvasXml(storedCanvasXml());
        request.setMaxDeterministicRepairRounds(3);
        request.setMessage("Fix only the cited spacing issue and preserve everything else.");

        service.continueDrawing(
                request,
                new DrawerContinuationContext(
                        "architecture",
                        CanvasMutationAuthorization.unrestricted()),
                new CapturingEmitter());

        assertEquals(0, routingService.calls);
        assertEquals(1, chatService.handleMessageStreamCalls);
        assertTrue(chatService.lastStreamMessage.contains("\"routeType\":\"edit_existing\""));
        assertTrue(chatService.lastStreamMessage.contains(
                "\"allowedTools\":[\"modify_diagram\",\"optimize_diagram\"]"));
        assertFalse(chatService.lastStreamMessage.contains("\"allowedTools\":[\"create_diagram\"]"));
        assertTrue(chatService.lastStreamMessage.contains(
                "\"repairTools\":[\"modify_diagram\",\"optimize_diagram\"]"));
        assertTrue(chatService.lastStreamMessage.contains("\"maxRepairRounds\":0"));
    }

    @Test
    public void modelAuthoredReasonCannotGrantDrawerContinuationTools() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = drawRoutingResult("edit_existing");
        routingResult.setReason("production_visual_review_continuation");
        ChatRequestDTO request = new ChatRequestDTO();
        request.setMessage("change the API label");
        request.setCanvasXml(storedCanvasXml());

        String routedMessage = buildRoutedMessage(service, request, routingResult, 1);

        assertTrue(routedMessage.contains("\"allowedTools\":[\"modify_diagram\"]"));
        assertFalse(routedMessage.contains(
                "\"allowedTools\":[\"modify_diagram\",\"optimize_diagram\"]"));
    }

    @Test
    public void unknownAndEvidenceRoutesNeverReceiveCanvasTools() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setMessage("answer from the guide");

        String unknown = buildRoutedMessage(service, request, drawRoutingResult("invented_route"), 1);
        String evidence = buildRoutedMessage(service, request, drawRoutingResult("answer_with_evidence"), 1);

        assertTrue(unknown.contains("\"allowedTools\""));
        assertTrue(evidence.contains("\"allowedTools\""));
        assertFalse(unknown.contains("\"allowedTools\":[\"create_diagram\"]"));
        assertFalse(evidence.contains("\"allowedTools\":[\"modify_diagram\"]"));
    }

    @Test
    public void shouldClampDeterministicRepairRoundSetting() throws Exception {
        AgentConversationService service = new AgentConversationService();

        assertEquals(0, normalizeDeterministicRepairRounds(service, null));
        assertEquals(0, normalizeDeterministicRepairRounds(service, -1));
        assertEquals(0, normalizeDeterministicRepairRounds(service, 0));
        assertEquals(2, normalizeDeterministicRepairRounds(service, 2));
        assertEquals(3, normalizeDeterministicRepairRounds(service, 9));
    }

    @Test
    public void finishRepairBriefOverridesVisualAnalysisFailureForLoopControl() throws Exception {
        AgentConversationService service = new AgentConversationService();
        Event event = mutationEvent(Map.of(
                "type", "drawio_done",
                "analysis", Map.of("valid", false),
                "repairBrief", "APPLIED. No blocking issues remain."
        ));

        assertEquals("CLEAN", mutationOutcome(service, event));
    }

    @Test
    public void structuralRepairBriefOverridesPassingAnalysisForLoopControl() throws Exception {
        AgentConversationService service = new AgentConversationService();
        Event event = mutationEvent(Map.of(
                "type", "drawio_done",
                "analysis", Map.of("valid", true),
                "repairBrief", "REPAIR REQUIRED: DUP_ID must be fixed."
        ));

        assertEquals("NEEDS_REPAIR", mutationOutcome(service, event));
    }

    @Test
    public void shouldPreferNewDeterministicRepairBudgetOverLegacyField() throws Exception {
        AgentConversationService service = new AgentConversationService();
        ChatRequestDTO request = new ChatRequestDTO();
        request.setMaxDeterministicRepairRounds(2);
        request.setMaxReviewIterations(3);

        assertEquals(Integer.valueOf(2), requestedDeterministicRepairRounds(service, request));
    }

    @Test
    public void shouldReadLegacyRepairBudgetWhenNewFieldIsAbsent() throws Exception {
        AgentConversationService service = new AgentConversationService();
        ChatRequestDTO request = new ChatRequestDTO();
        request.setMaxReviewIterations(3);

        assertEquals(Integer.valueOf(3), requestedDeterministicRepairRounds(service, request));
    }

    @Test
    public void shouldIncludeRepairBudgetAndHighLevelAllowedToolsInRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = drawRoutingResult("edit_existing");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage("update the API label");
        requestDTO.setCanvasXml("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"api\" value=\"API\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"120\" y=\"80\" width=\"100\" height=\"40\" as=\"geometry\"/>"
                + "</mxCell></root></mxGraphModel>");

        String routedMessage = buildRoutedMessage(service, requestDTO, routingResult, 1);

        assertTrue(routedMessage.contains("\"maxRepairRounds\":1"));
        assertTrue(routedMessage.contains("\"routeType\":\"edit_existing\""));
        assertFalse(routedMessage.contains("\"intent\""));
        assertFalse(routedMessage.contains("\"drawMode\""));
        assertFalse(routedMessage.contains("\"taskType\""));
        assertTrue(routedMessage.contains("\"allowedTools\""));
        assertTrue(routedMessage.contains("\"skillTools\""));
        assertTrue(routedMessage.contains("get_drawio_skill"));
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
    public void shouldDescribeExactInitialAndRepairToolsForEveryDrawingRoute() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        injectSkillContentProvider(service);
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setMessage("draw a flowchart");
        requestDTO.setCanvasXml(storedCanvasXml());
        Map<String, String> initialToolByRoute = Map.of(
                "create_new", "create_diagram",
                "edit_existing", "modify_diagram",
                "optimize_layout", "optimize_diagram");

        for (Map.Entry<String, String> entry : initialToolByRoute.entrySet()) {
            String routedMessage = buildRoutedMessage(
                    service, requestDTO, drawRoutingResult(entry.getKey()), 1);
            com.alibaba.fastjson.JSONObject routingJson = routedMessageJson(routedMessage);

            assertEquals(List.of(entry.getValue()),
                    routingJson.getJSONArray("allowedTools").toJavaList(String.class));
            assertEquals(List.of("modify_diagram", "optimize_diagram"),
                    routingJson.getJSONArray("repairTools").toJavaList(String.class));
            assertTrue(routedMessage.contains("get_drawio_skill"));
            assertTrue(routedMessage.contains("Self-repair rounds use only repairTools"));
            assertFalse(routedMessage.contains("reviewRepairTools"));
        }
    }

    @Test
    public void shouldLogDerivedToolGateForRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = drawRoutingResult("edit_existing");

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
                            && message.contains("routeType=edit_existing")
                            && message.contains("allowedTools=[modify_diagram]")
                            && message.contains("maxRepairRounds=0")));
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
        assertFalse(intentMessage.contains("[Canvas Summary]"));
        assertFalse(intentMessage.contains("hasCanvas=true"));
        assertFalse(intentMessage.contains("Main labels: API"));
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

        IntentRoutingResult routingResult = drawRoutingResult("edit_existing");

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

        IntentRoutingResult routingResult = drawRoutingResult("edit_existing");

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
    public void shouldRejectAnonymousRawCustomKeyRequestsBeforeModelWork() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setCustomApiKey("sk-user-owned");
        AppException error = assertAppException(() -> service.chat(requestDTO));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), error.getCode());
        assertEquals(0, intentRoutingService.calls);
        assertEquals(0, chatService.handleMessageCalls);
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

    @Test
    public void shouldRejectCanvasMutationWithoutADiagramIdBeforeDrawerWork() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO request = platformRequest();
        request.setDiagramId(null);
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"error\""));
        assertTrue(output.contains(ResponseCode.ILLEGAL_PARAMETER.getCode()));
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void shouldRejectTwentyFirstVerifiedPlatformBlockingRequestBeforeModelWork() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        for (int i = 0; i < 20; i++) {
            service.chat(verifiedPlatformRequest());
        }

        try {
            service.chat(verifiedPlatformRequest());
        } catch (PlatformDailyQuotaExceededException expected) {
            assertEquals(ResponseCode.PLATFORM_QUOTA_EXHAUSTED.getCode(), expected.getCode());
            assertEquals(20, intentRoutingService.calls);
            assertEquals(20, chatService.handleMessageCalls);
            return;
        }
        throw new AssertionError("expected verified user daily quota denial");
    }

    @Test
    public void shouldRejectVerifiedRawCustomKeyRequestsBeforeModelWork() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        ChatRequestDTO requestDTO = verifiedPlatformRequest();
        requestDTO.setCustomApiKey("sk-user-owned");
        AppException error = assertAppException(() -> service.chat(requestDTO));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), error.getCode());
        assertEquals(0, intentRoutingService.calls);
        assertEquals(0, chatService.handleMessageCalls);
    }

    @Test
    public void shouldResolveSavedCredentialForChatAndSkipVerifiedPlatformQuota() throws Exception {
        VerifiedUserPlatformQuotaService quotaService = new VerifiedUserPlatformQuotaService();
        AgentConversationService service = quotaAwareService(quotaService);
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        FakeModelCredentialService credentialService = new FakeModelCredentialService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);
        injectField(service, "modelCredentialService", credentialService);

        ChatRequestDTO requestDTO = verifiedPlatformRequest();
        requestDTO.setModelCredentialId("mcr_alice");
        service.chat(requestDTO);

        assertEquals("usr_alice", credentialService.resolvedUserId);
        assertEquals("mcr_alice", credentialService.resolvedCredentialId);
        assertEquals(1, intentRoutingService.calls);
        assertEquals(1, chatService.handleMessageCalls);
        assertEquals(0, quotaService.snapshot("usr_alice").getUsed());
        assertEquals("https://api.openai.com/v1", intentRoutingService.lastCommand.getCustomApiConfig().getBaseUrl());
        assertEquals("decrypted-api-key", intentRoutingService.lastCommand.getCustomApiConfig().getApiKey());
        assertEquals("/chat/completions", intentRoutingService.lastCommand.getCustomApiConfig().getCompletionsPath());
        assertEquals("gpt-4o", intentRoutingService.lastCommand.getCustomApiConfig().getModel());
    }

    @Test
    public void shouldRejectCrossUserCredentialBeforeModelWork() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        FakeModelCredentialService credentialService = new FakeModelCredentialService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);
        injectField(service, "modelCredentialService", credentialService);

        ChatRequestDTO requestDTO = verifiedPlatformRequest();
        requestDTO.setUserId("usr_bob");
        requestDTO.setModelCredentialId("mcr_alice");
        AppException error = assertAppException(() -> service.chat(requestDTO));

        assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), error.getCode());
        assertEquals("usr_bob", credentialService.resolvedUserId);
        assertEquals("mcr_alice", credentialService.resolvedCredentialId);
        assertEquals(0, intentRoutingService.calls);
        assertEquals(0, chatService.handleMessageCalls);
    }

    @Test
    public void shouldReturnTypedStreamErrorBeforeModelWorkWhenVerifiedDailyQuotaIsExhausted() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        CountingIntentRoutingService intentRoutingService = new CountingIntentRoutingService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", intentRoutingService);

        for (int i = 0; i < 20; i++) {
            service.stream(verifiedPlatformRequest(), new CapturingEmitter());
        }

        CapturingEmitter deniedEmitter = new CapturingEmitter();
        service.stream(verifiedPlatformRequest(), deniedEmitter);

        String output = String.join("\n", deniedEmitter.sent);
        assertTrue(output.contains("\"type\":\"error\""));
        assertTrue(output.contains(ResponseCode.PLATFORM_QUOTA_EXHAUSTED.getCode()));
        assertEquals(20, intentRoutingService.calls);
        assertEquals(20, chatService.handleMessageStreamCalls);
    }

    @Test
    public void shouldRecordSuccessfulRunTelemetryWithoutSensitiveContent() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());

        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setMessage("draw checkout flow sk-live-secret <mxGraphModel><root/></mxGraphModel>");

        service.chat(requestDTO);

        assertEquals(1, telemetryStore.runs.size());
        assertEquals("SUCCESS", telemetryStore.runs.get(0).getStatus());
        assertTrue(telemetryStore.steps.stream().anyMatch(step -> "routing".equals(step.getPhase())));
        assertTrue(telemetryStore.steps.stream().anyMatch(step -> "drawing".equals(step.getPhase())));
        assertTrue(telemetryStore.traceEvents.stream().anyMatch(event -> "HTTP_REQUEST_RECEIVED".equals(event.getEventType())));
        assertTrue(telemetryStore.traceEvents.stream().anyMatch(event -> "ROUTING_DECIDED".equals(event.getEventType())));
        assertFalse(telemetryStore.serializedRecords().contains("sk-live-secret"));
        assertFalse(telemetryStore.serializedRecords().contains("<mxGraphModel"));
    }

    @Test
    public void shouldCaptureRoutingStepInputAndOutputAgainstTheStepSpan() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_step_payload", null, null);
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "agentDebugTraceService", debugService);
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRunId("aru_step_payload");
        requestDTO.setMessage("draw a checkout flow");

        service.chat(requestDTO);

        String routingSpanId = telemetryStore.steps.stream()
                .filter(step -> "routing".equals(step.getPhase()))
                .findFirst()
                .orElseThrow()
                .getId();
        DebugTraceCapture input = debugStore.captures.stream()
                .filter(capture -> routingSpanId.equals(capture.getSpanId()))
                .filter(capture -> "INPUT".equals(capture.getPayloadKind()))
                .findFirst()
                .orElseThrow();
        DebugTraceCapture output = debugStore.captures.stream()
                .filter(capture -> routingSpanId.equals(capture.getSpanId()))
                .filter(capture -> "OUTPUT".equals(capture.getPayloadKind()))
                .findFirst()
                .orElseThrow();
        assertTrue(input.getContent().contains("draw a checkout flow"));
        assertTrue(output.getContent().contains("routeType"));
    }

    @Test
    public void shouldCaptureStructuredRunOutputWithFinalDiagramId() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_run_output", null, null);
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "agentDebugTraceService", debugService);
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRunId("aru_run_output");
        requestDTO.setRequestId("req_run_output");
        requestDTO.setDiagramId("diagram-final");

        service.chat(requestDTO);

        DebugTraceCapture output = debugStore.captures.stream()
                .filter(capture -> "OUTPUT".equals(capture.getPayloadKind()))
                .filter(capture -> "aru_run_output".equals(capture.getSpanId()))
                .findFirst()
                .orElseThrow();
        com.alibaba.fastjson.JSONObject content = com.alibaba.fastjson.JSON.parseObject(output.getContent());
        assertEquals("application/json", output.getContentType());
        assertEquals("user", content.getString("type"));
        assertEquals("ok", content.getString("content"));
        assertEquals("diagram-final", content.getString("diagramId"));
        assertEquals("req_run_output", content.getString("requestId"));
        assertEquals("aru_run_output", content.getString("runId"));
    }

    @Test
    public void shouldNotFailWhenDirectAnswerResolvesToNull() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_null_answer", null, null);
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "agentDebugTraceService", debugService);
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new NullDirectAnswerRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRunId("aru_null_answer");

        // A null direct answer must not raise (Map.of would NPE and flip the run to FAILED).
        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(requestDTO);

        assertEquals("user", response.getType());
        assertNull(response.getContent());
        String directAnswerSpanId = telemetryStore.steps.stream()
                .filter(step -> "direct_answer".equals(step.getPhase()))
                .findFirst()
                .orElseThrow()
                .getId();
        // The step must complete as OUTPUT, never be flipped to an ERROR span by a serialization NPE.
        assertTrue(debugStore.captures.stream().noneMatch(capture ->
                directAnswerSpanId.equals(capture.getSpanId()) && "ERROR".equals(capture.getPayloadKind())));
        assertTrue(debugStore.captures.stream().anyMatch(capture ->
                directAnswerSpanId.equals(capture.getSpanId()) && "OUTPUT".equals(capture.getPayloadKind())));
    }

    @Test
    public void evidenceAnswerStopsBeforeDrawerWhenWp5FeatureIsDisabled() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new EvidenceRoutingService());
        ChatRequestDTO request = platformRequest();
        request.setMessage("answer from Agile Practice Guide");

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(request);

        assertEquals("capability_unavailable", response.getType());
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void ordinaryDrawingContinuesWhenMaterialRetrievalAndShadowFlagsAreDisabled() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        injectField(service, "materialRagEnabled", false);
        injectField(service, "materialRetrievalShadowEnabled", false);
        ChatRequestDTO request = platformRequest();
        request.setSourceMode("NONE");

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(request);

        assertEquals("user", response.getType());
        assertEquals(1, chatService.handleMessageCalls);
    }

    @Test
    public void shadowRetrievalRecordsAnAttemptWithoutChangingTheDrawingResponse() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        AtomicInteger shadowAttempts = new AtomicInteger();
        AtomicInteger materialProbeCalls = new AtomicInteger();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        injectField(service, "materialRetrievalShadowEnabled", true);
        injectField(service, "requestProbeService",
                (org.zipp.ai.domain.retrieval.RequestProbeService) command -> {
                    materialProbeCalls.incrementAndGet();
                    throw new AssertionError("shadow probe must not affect the primary router");
                });
        injectField(service, "evidencePreparationModule", new org.zipp.ai.domain.retrieval.EvidencePreparationModule() {
            @Override
            public java.util.concurrent.CompletionStage<org.zipp.ai.domain.retrieval.PreparationOutcome> prepare(
                    org.zipp.ai.domain.retrieval.EvidencePreparationCommand command,
                    org.zipp.ai.domain.retrieval.RunResourceDomain resources,
                    org.zipp.ai.domain.retrieval.EvidenceProgressListener progress,
                    org.zipp.ai.domain.retrieval.CancellationSignal cancellation) {
                throw new AssertionError("shadow must not use synchronous evidence preparation");
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> observe(
                    org.zipp.ai.domain.retrieval.EvidencePreparationCommand command) {
                    shadowAttempts.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        });

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(platformRequest());

        assertEquals("user", response.getType());
        assertEquals(1, shadowAttempts.get());
        assertEquals(0, materialProbeCalls.get());
        assertEquals(1, chatService.handleMessageCalls);
    }

    @Test
    public void evidenceAnswerCommitsBeforeReturningAndNeverCallsDrawer() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        AtomicInteger commits = new AtomicInteger();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new EvidenceRoutingService());
        injectField(service, "canvasStateStore", new FixedCanvasStateStore(storedCanvasXml()));
        injectField(service, "materialRagEnabled", true);
        injectField(service, "groundedRunControlPort", new org.zipp.ai.domain.grounding.port.GroundedRunControlPort() {
            @Override public void start(RunIdentity identity) { }
            @Override public CancelResult cancel(RunIdentity identity) { return CancelResult.ALREADY_COMPLETED; }
        });
        injectField(service, "evidencePreparationModule",
                (org.zipp.ai.domain.retrieval.EvidencePreparationModule) (command, resources, progress, cancellation) -> {
                    resources.markPrepared();
                    org.zipp.ai.domain.retrieval.EvidenceBundle bundle = new org.zipp.ai.domain.retrieval.EvidenceBundle(
                            "bundle-1", command.requestId(), command.runId(), org.zipp.ai.domain.retrieval.SourceMode.AUTO,
                            List.of(new org.zipp.ai.domain.retrieval.EvidenceBundleItem(
                                    "E1", "evidence-1", "material-1", "version-1", "revision-1",
                                    "Agile Guide", 4, "TEXT", "Teams inspect progress every day")));
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new org.zipp.ai.domain.retrieval.PreparationOutcome.Ready(
                                    new org.zipp.ai.domain.retrieval.PreparedEvidence(bundle, resources),
                                    new org.zipp.ai.domain.retrieval.RetrievalDiagnostics(
                                            org.zipp.ai.domain.retrieval.RetrievalRoute.TEXT, List.of())));
                });
        org.zipp.ai.domain.citation.answer.AnswerClaim claim = new org.zipp.ai.domain.citation.answer.AnswerClaim(
                "C1", "Teams inspect progress every day", List.of("E1"),
                org.zipp.ai.domain.citation.answer.AnswerSupportType.DIRECT,
                List.of(new org.zipp.ai.domain.citation.model.valobj.SupportAtom(
                        "A1", "E1", "Teams inspect progress every day",
                        org.zipp.ai.domain.citation.model.valobj.SupportAtomRole.DIRECT_QUOTE)));
        injectField(service, "evidenceAnswerService", new org.zipp.ai.domain.citation.answer.EvidenceAnswerService(
                command -> new org.zipp.ai.domain.citation.answer.AnswerProposal(List.of(claim), List.of(), List.of()),
                new org.zipp.ai.domain.citation.answer.EvidenceAnswerGuard(requests -> List.of()),
                plan -> { commits.incrementAndGet(); return org.zipp.ai.domain.citation.answer.EvidenceAnswerCommitPort.CommitStatus.COMMITTED; }));
        ChatRequestDTO request = platformRequest();
        request.setRequestId("request-evidence-1");
        request.setResponseMessageId("message-evidence-1");
        request.setMessage("answer from Agile Practice Guide");

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(request);

        assertEquals("evidence_answer", response.getType());
        assertTrue(response.getContent().contains("[C1]"));
        assertEquals(1, commits.get());
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void singleReadyImageDirectRequestCommitsBeforeReturningAndNeverCallsDrawer() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        DirectImageRoutingService routingService = new DirectImageRoutingService();
        AtomicReference<org.zipp.ai.domain.multimodal.DirectImageConversionCommand> executed =
                new AtomicReference<>();
        String convertedXml = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"direct-node-a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"
                + "</root></mxGraphModel>";
        CanvasState saved = CanvasState.builder().userId("anon_123e4567-e89b-42d3-a456-426614174000")
                .diagramId("diagram-1").diagramType("flowchart").currentXml(convertedXml)
                .contentHash("direct-hash").version(1L).build();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", routingService);
        injectField(service, "canvasStateStore", new FixedCanvasStateStore(""));
        injectField(service, "requestSourceResolutionService",
                (org.zipp.ai.domain.retrieval.RequestSourceResolutionService) command ->
                        readyImageSourceSnapshot());
        injectField(service, "taskSourcePlanner",
                new org.zipp.ai.domain.multimodal.DefaultTaskSourcePlanner());
        injectField(service, "directImageConversionExecutionModule",
                (org.zipp.ai.domain.multimodal.DirectImageConversionExecutionModule)
                        (command, progress, cancellation) -> {
                            executed.set(command);
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                    new org.zipp.ai.domain.multimodal.DirectImageConversionOutcome.Committed(
                                            convertedXml,
                                            org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult
                                                    .created(saved)));
                        });
        ChatRequestDTO request = platformRequest();
        request.setSessionId(null);
        request.setRequestId("request-direct-1");
        request.setAttachmentUploadIds(List.of("upload-1"));
        request.setSelectedVersionIds(List.of("selected-version-1"));
        request.setSourceMode("EXPLICIT_ONLY");

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(request);

        assertEquals("drawio", response.getType());
        assertEquals(convertedXml, response.getContent());
        assertEquals(Long.valueOf(1L), response.getCanvasVersion());
        assertEquals("direct-hash", response.getContentHash());
        assertEquals("upload-1", executed.get().source().attachmentUploadId());
        assertEquals("session-1", executed.get().source().conversationId());
        assertEquals(List.of("selected-version-1"), executed.get().source().selectedVersionIds());
        assertTrue(routingService.lastCommand.getRequestProbe().hasSingleReadyImageAttachment());
        assertEquals(1, routingService.lastCommand.getRequestProbe().readyAttachmentCount());
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void singleReadyImageDirectStreamEmitsPersistedCanvasWithoutCallingDrawer() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        AtomicReference<org.zipp.ai.domain.multimodal.DirectImageConversionCommand> executed =
                new AtomicReference<>();
        String convertedXml = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + "<mxCell id=\"direct-node-a\" value=\"A\" vertex=\"1\" parent=\"1\"/>"
                + "</root></mxGraphModel>";
        CanvasState saved = CanvasState.builder().userId("anon_123e4567-e89b-42d3-a456-426614174000")
                .diagramId("diagram-1").diagramType("flowchart").currentXml(convertedXml)
                .contentHash("direct-hash").version(2L).build();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new DirectImageRoutingService());
        injectField(service, "canvasStateStore", new FixedCanvasStateStore(""));
        injectField(service, "requestSourceResolutionService",
                (org.zipp.ai.domain.retrieval.RequestSourceResolutionService) command ->
                        readyImageSourceSnapshot());
        injectField(service, "taskSourcePlanner",
                new org.zipp.ai.domain.multimodal.DefaultTaskSourcePlanner());
        injectField(service, "directImageConversionExecutionModule",
                (org.zipp.ai.domain.multimodal.DirectImageConversionExecutionModule)
                        (command, progress, cancellation) -> {
                            executed.set(command);
                            return java.util.concurrent.CompletableFuture.completedFuture(
                                        new org.zipp.ai.domain.multimodal.DirectImageConversionOutcome.Committed(
                                                convertedXml,
                                                org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult
                                                        .updated(saved)));
                        });
        ChatRequestDTO request = platformRequest();
        request.setSessionId(null);
        request.setRequestId("request-direct-stream-1");
        request.setAttachmentUploadIds(List.of("upload-1"));
        request.setSourceMode("EXPLICIT_ONLY");
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"drawio_done\""));
        assertTrue(output.contains("\"version\":2"));
        assertTrue(output.contains("\"contentHash\":\"direct-hash\""));
        assertTrue(output.contains("\"type\":\"done\""));
        assertEquals("session-1", executed.get().source().conversationId());
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void directConfirmationStreamPreservesTypedOutcomeAndDoesNotCallDrawer() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new DirectImageRoutingService());
        injectField(service, "canvasStateStore", new FixedCanvasStateStore(""));
        injectField(service, "requestSourceResolutionService",
                (org.zipp.ai.domain.retrieval.RequestSourceResolutionService) command ->
                        readyImageSourceSnapshot());
        injectField(service, "taskSourcePlanner",
                new org.zipp.ai.domain.multimodal.DefaultTaskSourcePlanner());
        injectField(service, "directImageConversionExecutionModule",
                (org.zipp.ai.domain.multimodal.DirectImageConversionExecutionModule)
                        (command, progress, cancellation) ->
                                java.util.concurrent.CompletableFuture.completedFuture(
                                        new org.zipp.ai.domain.multimodal.DirectImageConversionOutcome
                                                .NeedsConfirmation(List.of("AMBIGUOUS_DIRECTION"))));
        ChatRequestDTO request = platformRequest();
        request.setRequestId("request-direct-confirmation");
        request.setAttachmentUploadIds(List.of("upload-1"));
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(request, emitter);

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"degraded\""));
        assertTrue(output.contains("\"outcomeType\":\"direct_confirmation_required\""));
        assertTrue(output.contains("\"type\":\"done\""));
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void multipleAttachmentRequestDoesNotEnterSingleImageDirectConversion() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        AtomicInteger directExecutions = new AtomicInteger();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new DirectImageRoutingService());
        injectField(service, "canvasStateStore", new FixedCanvasStateStore(""));
        injectField(service, "requestSourceResolutionService",
                (org.zipp.ai.domain.retrieval.RequestSourceResolutionService) command ->
                        readyImageSourceSnapshot());
        injectField(service, "taskSourcePlanner",
                new org.zipp.ai.domain.multimodal.DefaultTaskSourcePlanner());
        injectField(service, "directImageConversionExecutionModule",
                (org.zipp.ai.domain.multimodal.DirectImageConversionExecutionModule)
                        (command, progress, cancellation) -> {
                            directExecutions.incrementAndGet();
                            throw new AssertionError("Multiple attachments must not enter direct conversion");
                        });
        ChatRequestDTO request = platformRequest();
        request.setRequestId("request-direct-multiple");
        request.setAttachmentUploadIds(List.of("upload-1", "upload-2"));
        request.setSourceMode("AUTO");

        // Multi-attachment requests stay on the normal drawing path.
        service.chat(request);

        assertEquals(0, directExecutions.get());
    }

    @Test
    public void probeAndEvidencePreparationShareOneResolvedSourceSnapshot() throws Exception {
        AgentConversationService service = quotaAwareService();
        AtomicInteger resolutions = new AtomicInteger();
        AtomicReference<org.zipp.ai.domain.retrieval.ResolvedSourceSet> probed = new AtomicReference<>();
        AtomicReference<org.zipp.ai.domain.retrieval.ResolvedSourceSet> prepared = new AtomicReference<>();
        org.zipp.ai.domain.retrieval.ResolvedSourceSet snapshot =
                new org.zipp.ai.domain.retrieval.ResolvedSourceSet(
                        org.zipp.ai.domain.retrieval.SourceMode.EXPLICIT_ONLY, List.of(), 1, 0);
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new EvidenceRoutingService());
        injectField(service, "materialRagEnabled", true);
        injectField(service, "requestSourceResolutionService",
                (org.zipp.ai.domain.retrieval.RequestSourceResolutionService) command -> {
                    resolutions.incrementAndGet();
                    return snapshot;
                });
        injectField(service, "requestProbeService",
                (org.zipp.ai.domain.retrieval.RequestProbeService) command -> {
                    probed.set(command.resolvedSources());
                    return new org.zipp.ai.domain.retrieval.RequestProbe(
                            snapshot.toProbe(), org.zipp.ai.domain.retrieval.CanvasProbe.unavailableProbe());
                });
        injectField(service, "groundedRunControlPort", new org.zipp.ai.domain.grounding.port.GroundedRunControlPort() {
            @Override public void start(RunIdentity identity) { }
            @Override public CancelResult cancel(RunIdentity identity) { return CancelResult.ALREADY_CANCELLED; }
        });
        injectField(service, "evidencePreparationModule",
                (org.zipp.ai.domain.retrieval.EvidencePreparationModule) (command, resources, progress, cancellation) -> {
                    prepared.set(command.resolvedSources());
                    return java.util.concurrent.CompletableFuture.completedFuture(
                            new org.zipp.ai.domain.retrieval.PreparationOutcome.Waiting(List.of()));
                });
        ChatRequestDTO request = platformRequest();
        request.setRequestId("request-source-snapshot-1");
        request.setMessage("answer from the selected upload");
        request.setSourceMode("NONE");
        request.setAttachmentUploadIds(List.of("upl-1"));

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(request);

        assertEquals("material_waiting", response.getType());
        assertEquals(1, resolutions.get());
        assertSame(snapshot, probed.get());
        assertSame(snapshot, prepared.get());
    }

    @Test
    public void sourceSnapshotInfrastructureFailureStopsBeforeLegacyDrawing() throws Exception {
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        injectField(service, "materialRagEnabled", true);
        injectField(service, "requestSourceResolutionService",
                (org.zipp.ai.domain.retrieval.RequestSourceResolutionService) command -> {
                    throw new IllegalStateException("snapshot store unavailable");
                });
        injectField(service, "requestProbeService",
                (org.zipp.ai.domain.retrieval.RequestProbeService) command ->
                        new org.zipp.ai.domain.retrieval.RequestProbe(
                                command.resolvedSources().toProbe(),
                                org.zipp.ai.domain.retrieval.CanvasProbe.unavailableProbe()));

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(platformRequest());

        assertEquals("source_resolution_failed", response.getType());
        assertEquals(0, chatService.handleMessageCalls);
        assertEquals(0, chatService.handleMessageStreamCalls);
    }

    @Test
    public void shouldAttachRequestAndRunIdsToBlockingChatResponseAndAdkRunContext() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());

        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRequestId("req-test-123");
        requestDTO.setRunId("aru_test_run_1");

        org.zipp.ai.api.dto.ChatResponseDTO response = service.chat(requestDTO);

        assertEquals("req-test-123", response.getRequestId());
        assertEquals("aru_test_run_1", response.getRunId());
        assertEquals("aru_test_run_1", telemetryStore.runs.get(0).getId());
        assertEquals("req-test-123", chatService.lastRunContext.requestId());
        assertEquals("aru_test_run_1", chatService.lastRunContext.runId());
    }

    @Test
    public void shouldSendStreamMetaEventAndPassRunContextToAdkStream() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        AgentConversationService service = quotaAwareService();
        CountingChatService chatService = new CountingChatService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());

        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRequestId("req-stream-123");
        requestDTO.setRunId("aru_stream_run_1");
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(requestDTO, emitter);

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"meta\""));
        assertTrue(output.contains("\"requestId\":\"req-stream-123\""));
        assertTrue(output.contains("\"runId\":\"aru_stream_run_1\""));
        assertEquals("aru_stream_run_1", telemetryStore.runs.get(0).getId());
        assertEquals("req-stream-123", chatService.lastStreamRunContext.requestId());
        assertEquals("aru_stream_run_1", chatService.lastStreamRunContext.runId());
        assertTrue(telemetryStore.traceEvents.stream().anyMatch(event -> "STREAM_META_SENT".equals(event.getEventType())));
        assertTrue(telemetryStore.traceEvents.stream().anyMatch(event -> "STREAM_DONE".equals(event.getEventType())));
    }

    @Test
    public void shouldCaptureAggregatedStreamOutputAgainstTheRunSpan() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_stream_payload", null, null);
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "agentDebugTraceService", debugService);
        injectField(service, "chatService", new StreamingContentChatService());
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRunId("aru_stream_payload");

        service.stream(requestDTO, new CapturingEmitter());

        DebugTraceCapture output = debugStore.captures.stream()
                .filter(capture -> "OUTPUT".equals(capture.getPayloadKind()))
                .filter(capture -> "aru_stream_payload".equals(capture.getSpanId()))
                .findFirst()
                .orElseThrow();
        assertEquals("aru_stream_payload", output.getSpanId());
        assertTrue(output.getContent().contains("streamed answer"));
    }

    @Test
    public void shouldKeepTheTrueLengthWhenStreamCaptureRetainsOnlyAPrefix() throws Exception {
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_long_stream", null, null);
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentDebugTraceService", debugService);
        injectField(service, "chatService", new LongStreamingContentChatService());
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRunId("aru_long_stream");

        service.stream(requestDTO, new CapturingEmitter());

        DebugTraceCapture output = debugStore.captures.stream()
                .filter(capture -> "OUTPUT".equals(capture.getPayloadKind()))
                .filter(capture -> "aru_long_stream".equals(capture.getSpanId()))
                .findFirst()
                .orElseThrow();
        assertEquals(Long.valueOf(70_000), output.getOriginalLength());
        assertTrue(output.isTruncated());
        assertTrue(output.getContent().length() < output.getOriginalLength());
        com.alibaba.fastjson.JSONObject structured = com.alibaba.fastjson.JSON.parseObject(output.getContent());
        assertTrue(structured.getString("content").startsWith("x"));
        assertEquals("aru_long_stream", structured.getString("runId"));
    }

    @Test
    public void debugCaptureFailureDoesNotAbortStreamingReply() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        FakeDebugTraceStore debugStore = new FakeDebugTraceStore();
        AgentDebugTraceService debugService = new AgentDebugTraceService(debugStore, null);
        debugService.enableControl("usr_admin", null, "aru_stream_capture_failure", null, null);
        debugStore.failCaptures = true;
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "agentDebugTraceService", debugService);
        injectField(service, "chatService", new StreamingContentChatService());
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setRunId("aru_stream_capture_failure");
        CapturingEmitter emitter = new CapturingEmitter();

        service.stream(requestDTO, emitter);

        assertTrue(emitter.completed);
        assertTrue(emitter.sent.stream().anyMatch(value -> value.contains("streamed answer")));
        assertEquals("SUCCESS", telemetryStore.runs.get(0).getStatus());
    }

    @Test
    public void shouldSeedTheDrawerDraftStateFromTheCurrentCanvas() throws Exception {
        InitialStateCapturingChatService chatService = new InitialStateCapturingChatService();
        AgentConversationService service = quotaAwareService();
        injectField(service, "chatService", chatService);
        injectField(service, "intentRoutingService", new CountingIntentRoutingService());
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setCanvasXml(storedCanvasXml());

        service.stream(requestDTO, new CapturingEmitter());

        assertTrue(String.valueOf(chatService.initialState.get("draft_diagram")).contains("Stored API"));
    }

    @Test
    public void shouldRecordFailedRunTelemetryWithoutPersistingErrorMessageContent() throws Exception {
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        AgentConversationService service = quotaAwareService();
        injectField(service, "agentUsageTelemetryService", fixedTelemetryService(telemetryStore));
        injectField(service, "chatService", new CountingChatService());
        injectField(service, "intentRoutingService", new FailingIntentRoutingService());

        try {
            service.chat(platformRequest());
        } catch (IllegalStateException expected) {
            assertEquals(1, telemetryStore.runs.size());
            assertEquals("FAILED", telemetryStore.runs.get(0).getStatus());
            assertEquals("IllegalStateException", telemetryStore.runs.get(0).getErrorClass());
            assertTrue(telemetryStore.steps.stream().anyMatch(step ->
                    "routing".equals(step.getPhase()) && "FAILED".equals(step.getStatus())));
            assertFalse(telemetryStore.serializedRecords().contains("sk-live-secret"));
            assertFalse(telemetryStore.serializedRecords().contains("<mxGraphModel"));
            return;
        }
        throw new AssertionError("expected routing failure");
    }

    private int normalizeDeterministicRepairRounds(AgentConversationService service, Integer value) throws Exception {
        // Exercise the private normalization boundary without widening production API surface.
        Method method = AgentConversationService.class.getDeclaredMethod("normalizeDeterministicRepairRounds", Integer.class);
        method.setAccessible(true);
        return (int) method.invoke(service, value);
    }

    private String mutationOutcome(AgentConversationService service, Event event) throws Exception {
        // Exercise loop authorization without widening the production API surface.
        Method method = AgentConversationService.class.getDeclaredMethod("mutationOutcome", Event.class);
        method.setAccessible(true);
        return method.invoke(service, event).toString();
    }

    private Event mutationEvent(Map<String, Object> response) {
        return Event.builder()
                .author("agent_drawer")
                .content(com.google.genai.types.Content.fromParts(
                        com.google.genai.types.Part.fromFunctionResponse("modify_diagram", response)))
                .build();
    }

    private Integer requestedDeterministicRepairRounds(AgentConversationService service,
                                                       ChatRequestDTO request) throws Exception {
        Method method = AgentConversationService.class.getDeclaredMethod(
                "requestedDeterministicRepairRounds", ChatRequestDTO.class);
        method.setAccessible(true);
        return (Integer) method.invoke(service, request);
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
                                      int maxDeterministicRepairRounds) throws Exception {
        Method method = AgentConversationService.class.getDeclaredMethod(
                "buildRoutedMessage",
                ChatRequestDTO.class,
                IntentRoutingResult.class,
                int.class,
                String.class,
                java.util.List.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, requestDTO, routingResult, maxDeterministicRepairRounds, requestDTO.getUserId(), null);
    }

    private com.alibaba.fastjson.JSONObject routedMessageJson(String routedMessage) {
        int jsonStart = routedMessage.indexOf('\n') + 1;
        int jsonEnd = routedMessage.indexOf("\n\n", jsonStart);
        return com.alibaba.fastjson.JSON.parseObject(routedMessage.substring(jsonStart, jsonEnd));
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
        return quotaAwareService(new VerifiedUserPlatformQuotaService());
    }

    private AgentConversationService quotaAwareService(VerifiedUserPlatformQuotaService quotaService) throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        injectSkillContentProvider(service);
        injectField(service, "streamResponseWriter", new DrawioStreamResponseWriter(new DrawioToolCallRenderer()));
        injectField(service, "anonymousDemoQuotaService", new AnonymousDemoQuotaService());
        injectField(service, "verifiedUserPlatformQuotaService", quotaService);
        return service;
    }

    private AgentUsageTelemetryService fixedTelemetryService(FakeAgentUsageTelemetryStore telemetryStore) {
        return new AgentUsageTelemetryService(
                telemetryStore,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }

    private ChatRequestDTO platformRequest() {
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId("300000");
        requestDTO.setUserId("anon_123e4567-e89b-42d3-a456-426614174000");
        requestDTO.setSessionId("session-1");
        requestDTO.setDiagramId("diagram-1");
        requestDTO.setMessage("draw a flowchart");
        return requestDTO;
    }

    private ChatRequestDTO verifiedPlatformRequest() {
        ChatRequestDTO requestDTO = platformRequest();
        requestDTO.setUserId("usr_alice");
        return requestDTO;
    }

    private org.zipp.ai.domain.retrieval.ResolvedSourceSet readyImageSourceSnapshot() {
        return new org.zipp.ai.domain.retrieval.ResolvedSourceSet(
                org.zipp.ai.domain.retrieval.SourceMode.EXPLICIT_ONLY,
                List.of(new org.zipp.ai.domain.retrieval.ResolvedSource(
                        "material-1", "version-1", "revision-1", "IMAGE",
                        org.zipp.ai.domain.material.model.valobj.MaterialScopeType.CONVERSATION,
                        "session-1", "READY",
                        org.zipp.ai.domain.retrieval.RequestSourceOrigin.ATTACHMENT,
                        false, true, false)), 0, 0);
    }

    private static IntentRoutingResult drawRoutingResult(String routeType) {
        IntentRoutingResult result = new IntentRoutingResult();
        result.setRouteType(routeType);
        result.setDiagramType("others");
        result.setSkillName("none");
        result.setAnswer("");
        result.setReason("test");
        return result;
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private AppException assertAppException(Runnable action) {
        try {
            action.run();
        } catch (AppException expected) {
            return expected;
        }
        throw new AssertionError("expected AppException");
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

        @Override
        public SkillSection buildSkillSectionWithMetadata(java.util.List<String> skillNames, String ownerId) {
            return SkillSection.empty();
        }
    }

    private static class CountingIntentRoutingService implements IIntentRoutingService {
        private int calls;
        private IntentRoutingCommand lastCommand;

        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            calls++;
            lastCommand = command;
            return drawRoutingResult("create_new");
        }
    }

    private static class NullDirectAnswerRoutingService implements IIntentRoutingService {
        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            // A direct reply whose answer resolves to null: the router chose answer_only but has no text.
            IntentRoutingResult result = new IntentRoutingResult();
            result.setRouteType("answer_only");
            result.setDiagramType("none");
            result.setSkillName("none");
            result.setAnswer(null);
            result.setReason("test");
            return result;
        }
    }

    private static class ReviewOnlyRoutingService implements IIntentRoutingService {
        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            IntentRoutingResult result = new IntentRoutingResult();
            result.setRouteType("review_only");
            result.setDiagramType("architecture");
            result.setSkillName("none");
            result.setAnswer("");
            result.setReason("test");
            return result;
        }
    }

    private static class EvidenceRoutingService implements IIntentRoutingService {
        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            IntentRoutingResult result = drawRoutingResult("answer_with_evidence");
            result.setEvidenceNeed("REQUIRED");
            result.setTargetNeed("NONE");
            return result;
        }
    }

    private static class DirectImageRoutingService implements IIntentRoutingService {
        private IntentRoutingCommand lastCommand;

        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            lastCommand = command;
            IntentRoutingResult result = drawRoutingResult("create_new");
            result.setDiagramType("flowchart");
            result.setSourceUse("DIRECT");
            return result;
        }
    }

    private static class FailingIntentRoutingService implements IIntentRoutingService {
        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            throw new IllegalStateException("boom sk-live-secret <mxGraphModel><root/></mxGraphModel>");
        }
    }

    private static class FakeModelCredentialService implements IModelCredentialService {
        private String resolvedUserId;
        private String resolvedCredentialId;

        @Override
        public ModelCredentialSummary create(CreateModelCredentialCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ModelCredentialSummary> list(String userId) {
            return List.of();
        }

        @Override
        public ModelCredentialSecret resolveForChat(String userId, String credentialId) {
            resolvedUserId = userId;
            resolvedCredentialId = credentialId;
            if (!"usr_alice".equals(userId) || !"mcr_alice".equals(credentialId)) {
                throw new IllegalArgumentException("model credential not found");
            }
            return ModelCredentialSecret.builder()
                    .id("mcr_alice")
                    .baseUrl("https://api.openai.com/v1")
                    .apiKey("decrypted-api-key")
                    .completionPath("/chat/completions")
                    .model("gpt-4o")
                    .build();
        }

        @Override
        public boolean disable(String userId, String credentialId) {
            return false;
        }

        @Override
        public boolean delete(String userId, String credentialId) {
            return false;
        }
    }

    private static class CountingChatService implements IChatService {
        private int handleMessageCalls;
        private int handleMessageStreamCalls;
        private AgentUsageTelemetryContext.RunContext lastRunContext;
        private AgentUsageTelemetryContext.RunContext lastStreamRunContext;
        private String lastStreamMessage = "";

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
        public List<String> handleMessage(String agentId,
                                          String userId,
                                          String sessionId,
                                          String message,
                                          AgentUsageTelemetryContext.RunContext runContext) {
            lastRunContext = runContext;
            return handleMessage(agentId, userId, sessionId, message);
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            handleMessageStreamCalls++;
            lastStreamMessage = message;
            return Flowable.empty();
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId,
                                                   String userId,
                                                   String sessionId,
                                                   String message,
                                                   AgentUsageTelemetryContext.RunContext runContext) {
            lastStreamRunContext = runContext;
            return handleMessageStream(agentId, userId, sessionId, message);
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
            return List.of("{\"type\":\"user\",\"content\":\"ok\"}");
        }
    }

    private static final class StreamingContentChatService extends CountingChatService {
        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            Event event = Event.builder()
                    .id("evt_stream_output")
                    .invocationId("inv_stream_output")
                    .author("drawing_agent")
                    .content(com.google.genai.types.Content.builder()
                            .role("model")
                            .parts(List.of(com.google.genai.types.Part.fromText("streamed answer")))
                            .build())
                    .partial(false)
                    .build();
            return Flowable.just(event);
        }
    }

    private static final class InitialStateCapturingChatService extends CountingChatService {
        private Map<String, Object> initialState = Map.of();

        @Override
        public Flowable<Event> handleMessageStream(String agentId,
                                                   String userId,
                                                   String sessionId,
                                                   String message,
                                                   AgentUsageTelemetryContext.RunContext runContext,
                                                   Map<String, Object> initialState) {
            this.initialState = initialState;
            return Flowable.empty();
        }
    }

    private static final class LongStreamingContentChatService extends CountingChatService {
        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            Event event = Event.builder()
                    .id("evt_long_stream_output")
                    .invocationId("inv_long_stream_output")
                    .author("drawing_agent")
                    .content(com.google.genai.types.Content.builder()
                            .role("model")
                            .parts(List.of(com.google.genai.types.Part.fromText("x".repeat(70_000))))
                            .build())
                    .partial(false)
                    .build();
            return Flowable.just(event);
        }
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private final List<DebugTraceControl> controls = new ArrayList<>();
        private final List<DebugTraceCapture> captures = new ArrayList<>();
        private boolean failCaptures;

        @Override public void insertControl(DebugTraceControl control) { controls.add(control); }
        @Override public List<DebugTraceControl> listEnabledControls() { return controls; }
        @Override public void insertCapture(DebugTraceCapture capture) {
            if (failCaptures) throw new IllegalStateException("capture unavailable");
            captures.add(capture);
        }
        @Override public int deleteExpiredContent(Instant now) { return 0; }
        @Override public int extendRunContentExpiry(String runId, Instant expiresAt) { return 0; }
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
