package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.trigger.http.service.AgentConversationService;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentConversationServiceTest {

    @Test
    public void shouldClampFrontendReviewIterationSetting() throws Exception {
        AgentConversationService service = new AgentConversationService();

        assertEquals(2, normalizeMaxReviewIterations(service, null));
        assertEquals(0, normalizeMaxReviewIterations(service, -1));
        assertEquals(0, normalizeMaxReviewIterations(service, 0));
        assertEquals(2, normalizeMaxReviewIterations(service, 2));
        assertEquals(3, normalizeMaxReviewIterations(service, 9));
    }

    @Test
    public void shouldIncludeReviewBudgetAndAllowedToolsInRoutedMessage() throws Exception {
        AgentConversationService service = new AgentConversationService();
        IntentRoutingResult routingResult = IntentRoutingResult.fallbackDrawAction("test");
        routingResult.setTaskType("patch_existing");

        String routedMessage = buildRoutedMessage(service, "update the API label", routingResult, 1);

        assertTrue(routedMessage.contains("\"maxReviewIterations\":1"));
        assertTrue(routedMessage.contains("\"allowedTools\""));
        assertTrue(routedMessage.contains("find_cells"));
        assertTrue(routedMessage.contains("update_cells"));
        assertTrue(routedMessage.contains("validate_diagram"));
        assertFalse(routedMessage.contains("display_diagram\",\"append_diagram"));
    }

    private int normalizeMaxReviewIterations(AgentConversationService service, Integer value) throws Exception {
        // Exercise the private normalization boundary without widening production API surface.
        Method method = AgentConversationService.class.getDeclaredMethod("normalizeMaxReviewIterations", Integer.class);
        method.setAccessible(true);
        return (int) method.invoke(service, value);
    }

    private String buildRoutedMessage(AgentConversationService service,
                                      String message,
                                      IntentRoutingResult routingResult,
                                      int maxReviewIterations) throws Exception {
        Method method = AgentConversationService.class.getDeclaredMethod(
                "buildRoutedMessage",
                String.class,
                IntentRoutingResult.class,
                org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext.class,
                int.class
        );
        method.setAccessible(true);
        return (String) method.invoke(service, message, routingResult, null, maxReviewIterations);
    }

}
