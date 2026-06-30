package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.trigger.http.service.DrawioPromptContextBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    public void shouldIncludeReviewBudgetAndAllowedToolsInRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        injectPromptContextBuilder(service);
        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setTaskType("patch_existing");

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
        assertTrue(routedMessage.contains("inspect_canvas"));
        assertFalse(routedMessage.contains("find_cells"));
        assertFalse(routedMessage.contains("update_cells"));
        assertFalse(routedMessage.contains("validate_diagram"));
        assertTrue(routedMessage.contains("[Patch Target Cells]"));
        assertTrue(routedMessage.contains("node id=api label=\"API\""));
        assertFalse(routedMessage.contains("<mxGraphModel"));
        assertFalse(routedMessage.contains("display_diagram\",\"append_diagram"));
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

}
