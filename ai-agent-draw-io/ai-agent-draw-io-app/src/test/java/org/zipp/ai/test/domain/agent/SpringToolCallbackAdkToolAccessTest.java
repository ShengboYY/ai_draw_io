package org.zipp.ai.test.domain.agent;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.Session;
import com.google.adk.tools.ToolContext;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.tool.DrawioToolAccessContext;
import org.zipp.ai.domain.agent.service.armory.matter.tool.SpringToolCallbackAdkTool;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpringToolCallbackAdkToolAccessTest {

    @Test
    public void successfulCanvasMutationAdvancesTheSessionToRepairTools() {
        String sessionId = "create-session";
        String runId = "create-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM));
            SpringToolCallbackAdkTool tool = new SpringToolCallbackAdkTool(callback(
                    DrawioCanvasToolNames.CREATE_DIAGRAM,
                    "{\"type\":\"drawio_done\",\"content\":\"<mxGraphModel><root>"
                            + "<mxCell id='0'/><mxCell id='1' parent='0'/>"
                            + "<mxCell id='2' value='A' vertex='1' parent='1'>"
                            + "<mxGeometry x='100' y='100' width='120' height='60' as='geometry'/>"
                            + "</mxCell></root></mxGraphModel>\"}"));

            tool.runAsync(Map.of(), toolContext(sessionId, runId)).blockingGet();

            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
        } finally {
            AgentUsageTelemetryContext.clearInvocation(invocationId(sessionId));
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
    }

    @Test
    public void rejectedCanvasMutationKeepsTheInitialToolPhase() {
        String sessionId = "failed-create-session";
        String runId = "failed-create-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));
            SpringToolCallbackAdkTool tool = new SpringToolCallbackAdkTool(callback(
                    DrawioCanvasToolNames.CREATE_DIAGRAM,
                    "{\"type\":\"tool_error\",\"content\":\"invalid XML\"}"));

            tool.runAsync(Map.of(), toolContext(sessionId, runId)).blockingGet();

            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
        } finally {
            AgentUsageTelemetryContext.clearInvocation(invocationId(sessionId));
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
    }

    @Test
    public void unknownCanvasResultDoesNotUnlockRepairTools() {
        String sessionId = "unknown-result-session";
        String runId = "unknown-result-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));
            SpringToolCallbackAdkTool tool = new SpringToolCallbackAdkTool(callback(
                    DrawioCanvasToolNames.CREATE_DIAGRAM,
                    "{\"type\":\"unexpected_result\",\"content\":\"not applied\"}"));

            tool.runAsync(Map.of(), toolContext(sessionId, runId)).blockingGet();

            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
        } finally {
            AgentUsageTelemetryContext.clearInvocation(invocationId(sessionId));
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
    }

    @Test
    public void knownSuccessTypeWithoutAppliedPayloadKeepsTheInitialToolPhase() {
        String sessionId = "empty-success-session";
        String runId = "empty-success-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));
            SpringToolCallbackAdkTool tool = new SpringToolCallbackAdkTool(callback(
                    DrawioCanvasToolNames.CREATE_DIAGRAM,
                    "{\"type\":\"drawio_done\"}"));

            tool.runAsync(Map.of(), toolContext(sessionId, runId)).blockingGet();

            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
        } finally {
            AgentUsageTelemetryContext.clearInvocation(invocationId(sessionId));
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
    }

    @Test
    public void nonCanvasToolDoesNotReleaseAnInitialMutationInFlight() {
        String sessionId = "concurrent-skill-session";
        String runId = "concurrent-skill-run";
        try {
            openPolicy(
                    sessionId,
                    runId,
                    List.of(DrawioCanvasToolNames.CREATE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));
            assertTrue(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            SpringToolCallbackAdkTool skillTool = new SpringToolCallbackAdkTool(callback(
                    "get_drawio_skill",
                    "{\"type\":\"skill_result\",\"content\":\"rules\"}"));

            skillTool.runAsync(Map.of(), toolContext(sessionId, runId)).blockingGet();

            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.CREATE_DIAGRAM));
            assertFalse(DrawioToolAccessContext.tryStartCanvasTool(
                    sessionId, runId, DrawioCanvasToolNames.MODIFY_DIAGRAM));
        } finally {
            AgentUsageTelemetryContext.clearInvocation(invocationId(sessionId));
            DrawioToolAccessContext.closeSession(sessionId, runId);
        }
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

    private ToolCallback callback(String name, String response) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test callback")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return response;
            }
        };
    }

    private ToolContext toolContext(String sessionId, String runId) {
        LlmAgent agent = LlmAgent.builder().name("drawing_agent").model("test-model").build();
        Session session = Session.builder(sessionId).appName("test").userId("usr_1").build();
        String invocationId = invocationId(sessionId);
        AgentUsageTelemetryContext.RunContext runContext = new AgentUsageTelemetryContext.RunContext(
                runId, "request_1", "diagram_1", "usr_1", "drawing_agent", "draw",
                "platform", "", "test", "test-model", "drawing");
        AgentUsageTelemetryContext.InvocationState invocationState =
                AgentUsageTelemetryContext.newInvocationState(runContext);
        // Production registers this correlation in AgentUsageTelemetryPlugin.beforeRunCallback.
        AgentUsageTelemetryContext.registerInvocation(invocationId, invocationState.stateDelta());
        InvocationContext invocation = InvocationContext.builder()
                .invocationId(invocationId)
                .agent(agent)
                .session(session)
                .runConfig(RunConfig.builder().build())
                .build();
        return ToolContext.builder(invocation)
                .actions(new EventActions())
                .functionCallId("function_1")
                .build();
    }

    private String invocationId(String sessionId) {
        return "inv_" + sessionId;
    }
}
