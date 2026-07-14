package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.tool.DrawioToolAccessContext;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioToolAccessContextTest {

    @Test
    public void routedRepairRunCannotCallCreateDiagram() {
        String sessionId = "visual-repair-session";
        String runId = "visual-repair-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM),
                    List.of());

            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(sessionId, runId, "get_drawio_skill"));
        } finally {
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
    }

    @Test
    public void initialMutationIsReservedAndThenSwitchesToRepairTools() {
        String sessionId = "create-session";
        String runId = "create-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM));

            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));

            DrawioToolAccessContext.finishCanvasTool(sessionId, runId, false);
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));

            DrawioToolAccessContext.finishCanvasTool(sessionId, runId, true);
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM));
        } finally {
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
    }

    @Test
    public void concurrentRunCannotOverwriteOrClearTheSessionOwner() {
        String sessionId = "shared-session";
        String firstRunId = "first-run";
        String secondRunId = "second-run";
        try {
            openPolicy(
                    sessionId,
                    firstRunId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));

            try {
                DrawioToolAccessContext.openSession(sessionId, secondRunId);
                throw new AssertionError("expected concurrent routed run rejection");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("active routed run"));
            }

            assertFalse(DrawioToolAccessContext.closeSession(sessionId, secondRunId));
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, "", DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, firstRunId, DrawioCanvasToolNames.CREATE_DIAGRAM));
        } finally {
            DrawioToolAccessContext.closeSession(sessionId, firstRunId);
        }
    }

    @Test
    public void closingOwnedSessionRemovesTheToolPolicy() {
        String sessionId = "cleared-session";
        String runId = "cleared-run";
        openPolicy(
                sessionId,
                runId,
                List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));

        assertTrue(DrawioToolAccessContext.closeSession(sessionId, runId));

        // Unrouted calls continue to rely on the agent's declared tools.
        assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
        assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
    }

    private void openPolicy(String sessionId,
                            String runId,
                            List<String> initialTools,
                            List<String> repairTools) {
        DrawioToolAccessContext.openSession(sessionId, runId);
        DrawioToolAccessContext.applyToolPolicy(
                runId,
                DrawioToolAccessContext.ToolPolicy.of(initialTools, repairTools));
    }
}
