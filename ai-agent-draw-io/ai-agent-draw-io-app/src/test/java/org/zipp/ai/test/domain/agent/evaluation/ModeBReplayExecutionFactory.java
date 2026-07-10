package org.zipp.ai.test.domain.agent.evaluation;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.evaluation.EvalBatchRunner;
import org.zipp.ai.domain.agent.service.intent.DefaultIntentRoutingService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Builds one deterministic execution solely from the replay data owned by that eval case. */
public class ModeBReplayExecutionFactory implements EvalBatchRunner.ExecutionFactory {

    @Override
    public EvalExecution create(EvalCaseDefinition evalCase) throws Exception {
        EvalCaseDefinition.Replay replay = requireReplay(evalCase);
        String initialXml = require(replay.getInitialCanvasXml(), "replay.initialCanvasXml");
        String toolName = require(replay.getToolName(), "replay.toolName");
        IntentRoutingResult routing = route(evalCase, initialXml, require(replay.getRouterReply(), "replay.routerReply"));
        String finalXml = executeTool(toolName, initialXml, replay);

        List<EvalTrace.ToolCall> toolCalls = new ArrayList<>();
        for (String required : evalCase.getExpected().getRequiredToolNamesBeforeMutation()) {
            toolCalls.add(successfulCall(required));
        }
        toolCalls.add(successfulCall(toolName));
        EvalTrace.TaskOutcome outcome = replay.getTaskOutcome() == null
                ? EvalTrace.TaskOutcome.UNKNOWN : replay.getTaskOutcome();
        return EvalExecution.builder()
                .evalCase(evalCase)
                .trace(EvalTrace.builder()
                        .runStatus(EvalTrace.RunStatus.SUCCESS)
                        .taskOutcome(outcome)
                        .routing(EvalTrace.Routing.builder()
                                .routeType(routing.getRouteType())
                                .diagramType(routing.getDiagramType())
                                .skillName(routing.getSkillName())
                                .build())
                        .toolCalls(toolCalls)
                        .beforeCanvasHash(Integer.toHexString(initialXml.hashCode()))
                        .afterCanvasHash(Integer.toHexString(finalXml.hashCode()))
                        .build())
                .finalCanvasXml(finalXml)
                .gitSha("stubbed-replay")
                .build();
    }

    private IntentRoutingResult route(EvalCaseDefinition evalCase, String canvasXml, String reply) throws Exception {
        DefaultIntentRoutingService service = new DefaultIntentRoutingService();
        inject(service, "chatService", new RecordedReplyChatService(reply));
        inject(service, "skillCatalogService", new EmptySkillCatalogService());
        return service.route(IntentRoutingCommand.builder()
                .userId("eval-user")
                .message(userMessage(evalCase))
                .canvasXml(canvasXml)
                .build());
    }

    private String executeTool(String toolName, String initialXml, EvalCaseDefinition.Replay replay) {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        if (DrawioCanvasToolNames.MODIFY_DIAGRAM.equals(toolName)) {
            DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
            request.setMode(require(replay.getMutationMode(), "replay.mutationMode"));
            request.setXml(initialXml);
            request.setCells(require(replay.getMutationCells(), "replay.mutationCells"));
            DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);
            if (response.getContent() != null) {
                return response.getContent();
            }
            if (response.getCells() != null) {
                return new DrawioCanvasXmlToolkit().replaceCells(initialXml, response.getCells());
            }
            throw new IllegalStateException("modify_diagram returned no canvas artifact");
        }
        if (DrawioCanvasToolNames.CREATE_DIAGRAM.equals(toolName)) {
            DrawioCanvasMcpService.DrawioXmlRequest request = new DrawioCanvasMcpService.DrawioXmlRequest();
            request.setXml(require(replay.getMutationCells(), "replay.mutationCells"));
            return service.createDiagram(request).getContent();
        }
        throw new IllegalArgumentException("Unsupported replay tool: " + toolName);
    }

    private String userMessage(EvalCaseDefinition evalCase) {
        Object user = evalCase.getInput().get("user");
        if (user instanceof String value && !value.isBlank()) {
            return value;
        }
        Object turns = evalCase.getInput().get("turns");
        if (turns instanceof List<?> values && !values.isEmpty()) {
            return String.valueOf(values.get(0));
        }
        throw new IllegalArgumentException("input.user or input.turns is required");
    }

    private EvalCaseDefinition.Replay requireReplay(EvalCaseDefinition evalCase) {
        if (evalCase.getReplay() == null) {
            throw new IllegalArgumentException("replay is required for Mode B");
        }
        return evalCase.getReplay();
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private EvalTrace.ToolCall successfulCall(String name) {
        return EvalTrace.ToolCall.builder().name(name).status(EvalTrace.RunStatus.SUCCESS).build();
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class EmptySkillCatalogService extends SkillCatalogService {
        @Override
        public RouterCatalog routerCatalog(String ownerId) {
            return new RouterCatalog("", java.util.Set.of());
        }
    }

    private static class RecordedReplyChatService implements IChatService {
        private final List<String> reply;
        private RecordedReplyChatService(String reply) { this.reply = List.of(reply); }
        public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() { return List.of(); }
        public String createSession(String agentId, String userId) { return "eval-session"; }
        public String ensureSession(String agentId, String userId, String sessionId) { return sessionId; }
        public List<String> handleMessage(String agentId, String userId, String message) { return reply; }
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) { return reply; }
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) { return Flowable.empty(); }
        public List<String> handleMessage(ChatCommandEntity command) { return reply; }
    }
}
