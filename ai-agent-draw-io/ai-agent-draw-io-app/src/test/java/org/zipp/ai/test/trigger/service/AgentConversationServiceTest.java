package org.zipp.ai.test.trigger.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.trigger.http.service.DrawioPromptContextBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    public void shouldLogDerivedToolGateForRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setDrawMode("edit_existing");
        routingResult.setTaskType("edit_existing");

        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setUserId("alice");
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
                    .anyMatch(message -> message.contains("[draw-route] userId=alice")
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
        return (String) method.invoke(service, requestDTO, routingResult, null, maxReviewIterations, "alice", null);
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

}
