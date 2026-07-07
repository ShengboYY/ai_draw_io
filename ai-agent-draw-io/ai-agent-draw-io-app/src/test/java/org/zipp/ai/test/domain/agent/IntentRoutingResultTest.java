package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;

import static org.junit.Assert.assertEquals;

public class IntentRoutingResultTest {

    @Test
    public void shouldFailClosedToClarifyWithoutCanvasMutation() {
        IntentRoutingResult result = IntentRoutingResult.clarifyFallback("fallback");

        assertEquals("clarify", result.getRouteType());
        assertEquals("none", result.getDiagramType());
        assertEquals("none", result.getSkillName());
        assertEquals(Boolean.FALSE, result.getNeedsCanvasQuality());
        assertEquals(Boolean.FALSE, result.getNeedsSemanticReview());
        assertEquals("general", result.getAnswerMode());
    }

}
