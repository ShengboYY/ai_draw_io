package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.tool.DrawioToolAccessContext;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioToolAccessContextTest {

    @Test
    public void routedRepairSessionCannotCallCreateDiagram() {
        String sessionId = "visual-repair-session";
        try {
            DrawioToolAccessContext.bindSession(sessionId, List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));

            assertTrue(DrawioToolAccessContext.allowsCanvasTool(sessionId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
            assertFalse(DrawioToolAccessContext.allowsCanvasTool(sessionId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertTrue(DrawioToolAccessContext.allowsCanvasTool(sessionId, "get_drawio_skill"));
        } finally {
            DrawioToolAccessContext.clearSession(sessionId);
        }
    }
}
