package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;

import static org.junit.Assert.assertEquals;

public class IntentRoutingResultTest {

    @Test
    public void shouldDefaultFallbackDrawActionToCreateNewTaskType() {
        IntentRoutingResult result = IntentRoutingResult.fallbackDrawAction("fallback");

        assertEquals("draw_action", result.getIntent());
        assertEquals("new_diagram", result.getDrawMode());
        assertEquals("create_new", result.getTaskType());
    }

}
