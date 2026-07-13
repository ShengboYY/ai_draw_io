package org.zipp.ai.trigger.evaluation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;
import org.zipp.ai.domain.agent.service.evaluation.EvalInfrastructureException;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import java.util.ArrayList;
import java.util.List;

/** Production Mode C drawing adapter; it addresses the drawing agent directly and skips routing. */
@Component
public class ProductionDrawingLiveEvalAdapter implements LiveEvalRunner.LiveExecutionFactory {
    private static final String EMPTY_CANVAS = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
    private final IChatService chat;
    private final String drawingAgentId;
    private final String runtimeModelVersion;
    private final String gitSha;
    private final CanvasXmlContentHasher hasher = new CanvasXmlContentHasher();

    public ProductionDrawingLiveEvalAdapter(IChatService chat,
            @Value("${zipp.evaluation.drawing-agent-id:300000}") String drawingAgentId,
            @Value("${zipp.evaluation.live-model-version:gpt-5.5}") String runtimeModelVersion,
            @Value("${GIT_SHA:unknown}") String gitSha) {
        this.chat = chat; this.drawingAgentId = drawingAgentId;
        this.runtimeModelVersion = runtimeModelVersion; this.gitSha = gitSha;
    }

    @Override
    public EvalExecution execute(EvalCaseDefinition evalCase) {
        verifyFrozenModel(evalCase);
        String initialXml = evalCase.getReplay() == null
                ? EMPTY_CANVAS : StringUtils.defaultIfBlank(evalCase.getReplay().getInitialCanvasXml(), EMPTY_CANVAS);
        List<String> replies;
        try {
            String userId = "eval-drawing-system";
            String sessionId = chat.createSession(drawingAgentId, userId);
            replies = chat.handleMessage(drawingAgentId, userId, sessionId, prompt(evalCase, initialXml));
        } catch (RuntimeException error) {
            throw new EvalInfrastructureException("drawing evaluation call failed", error);
        }
        DrawingReply projection = project(replies, initialXml);
        String finalXml = projection.finalXml();
        boolean changed = !hasher.hash(initialXml).equals(hasher.hash(finalXml));
        EvalTrace trace = EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(changed || !Boolean.TRUE.equals(evalCase.getExpected().getRequireCanvasChange())
                        ? EvalTrace.TaskOutcome.FULFILLED : EvalTrace.TaskOutcome.NOT_FULFILLED)
                .toolCalls(projection.toolCalls())
                .beforeCanvasHash(hasher.hash(initialXml)).afterCanvasHash(hasher.hash(finalXml)).build();
        return EvalExecution.builder().evalCase(evalCase).trace(trace).initialCanvasXml(initialXml)
                .finalCanvasXml(finalXml).responseText(String.join("\n", replies)).gitSha(gitSha).build();
    }

    private String prompt(EvalCaseDefinition evalCase, String initialXml) {
        Object user = evalCase.getInput().get("user");
        if (!(user instanceof String task) || task.isBlank()) {
            throw new IllegalArgumentException("Drawing Evaluation Case requires input.user");
        }
        return "Evaluation drawing task. Execute the drawing tools directly; do not classify intent.\n"
                + "Diagram type: " + StringUtils.defaultString(evalCase.getDiagramType(), "unknown") + "\n"
                + "Task: " + task + "\nCurrent canvas XML:\n" + initialXml;
    }

    private DrawingReply project(List<String> replies, String initialXml) {
        String result = initialXml;
        List<EvalTrace.ToolCall> calls = new ArrayList<>();
        for (String reply : replies == null ? List.<String>of() : replies) {
            if (StringUtils.isBlank(reply)) continue;
            if (reply.trim().startsWith("<mxGraphModel")) result = reply.trim();
            try {
                JSONObject value = JSON.parseObject(reply);
                if ("drawio_done".equals(value.getString("type"))
                        && StringUtils.isNotBlank(value.getString("content"))) result = value.getString("content");
                if (isCellPatch(value) && StringUtils.isNotBlank(value.getString("cells"))) {
                    result = new DrawioCanvasXmlToolkit().replaceCells(result, value.getString("cells"));
                }
                String toolName = value.getString("toolName");
                if (StringUtils.isNotBlank(toolName)) {
                    EvalTrace.RunStatus status = "SUCCESS".equalsIgnoreCase(value.getString("toolStatus"))
                            ? EvalTrace.RunStatus.SUCCESS : EvalTrace.RunStatus.FAILED;
                    calls.add(EvalTrace.ToolCall.builder().name(toolName).status(status).build());
                }
            } catch (RuntimeException ignored) {
                // Text explanations are retained as response evidence but are not canvas artifacts.
            }
        }
        return new DrawingReply(result, calls);
    }

    private boolean isCellPatch(JSONObject value) {
        String type = value.getString("type");
        String mode = value.getString("mode");
        return DrawioCanvasToolNames.PATCH_CELLS.equals(type)
                || DrawioCanvasToolNames.MODIFY_DIAGRAM.equals(type)
                && List.of("patch", "append", "replace_cells").contains(mode);
    }

    private void verifyFrozenModel(EvalCaseDefinition evalCase) {
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        if (profile != null && StringUtils.isNotBlank(profile.getModel())
                && !profile.getModel().equals(runtimeModelVersion)) {
            throw new IllegalStateException("drawing runtime model does not match the frozen Evaluation Profile");
        }
    }

    private record DrawingReply(String finalXml, List<EvalTrace.ToolCall> toolCalls) { }
}
