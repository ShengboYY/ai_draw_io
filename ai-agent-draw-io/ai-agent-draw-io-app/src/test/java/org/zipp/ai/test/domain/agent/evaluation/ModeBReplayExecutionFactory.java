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

    private final String gitSha;

    public ModeBReplayExecutionFactory() {
        this(System.getProperty("eval.gitSha", "test-fixture-sha"));
    }

    public ModeBReplayExecutionFactory(String gitSha) {
        this.gitSha = gitSha;
    }

    @Override
    public EvalExecution create(EvalCaseDefinition evalCase) throws Exception {
        EvalCaseDefinition.Replay replay = requireReplay(evalCase);
        String initialXml = require(replay.getInitialCanvasXml(), "replay.initialCanvasXml");
        if (replay.getTurns() != null && !replay.getTurns().isEmpty()) {
            return createMultiTurn(evalCase, replay, initialXml);
        }
        IntentRoutingResult routing = route(userMessage(evalCase), initialXml, require(replay.getRouterReply(), "replay.routerReply"));

        List<EvalTrace.ToolCall> toolCalls = new ArrayList<>();
        for (String required : evalCase.getExpected().getRequiredToolNamesBeforeMutation()) {
            toolCalls.add(successfulCall(required));
        }
        String finalXml = initialXml;
        for (EvalCaseDefinition.ReplayToolCall call : replay.getToolCalls()) {
            finalXml = executeTool(call, finalXml);
            toolCalls.add(successfulCall(call.getName()));
        }
        EvalTrace.TaskOutcome outcome = replay.getTaskOutcome() == null
                ? EvalTrace.TaskOutcome.UNKNOWN : replay.getTaskOutcome();
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        return EvalExecution.builder()
                .evalCase(evalCase)
                .trace(EvalTrace.builder()
                        .runStatus(EvalTrace.RunStatus.SUCCESS)
                        .taskOutcome(outcome)
                        .routing(EvalTrace.Routing.builder()
                                .routeType(routing.getRouteType())
                                .diagramType(routing.getDiagramType())
                                .skillName(routing.getSkillName())
                                .needsCanvasQuality(routing.getNeedsCanvasQuality())
                                .needsSemanticReview(routing.getNeedsSemanticReview())
                                .answerMode(routing.getAnswerMode())
                                .build())
                        .steps(List.of(
                                EvalTrace.Step.builder().phase("routing").agentId("300010")
                                        .status(EvalTrace.RunStatus.SUCCESS).build(),
                                EvalTrace.Step.builder().phase("drawing").agentId("300000")
                                        .status(EvalTrace.RunStatus.SUCCESS).build()))
                        .toolCalls(toolCalls)
                        .beforeCanvasHash(Integer.toHexString(initialXml.hashCode()))
                        .afterCanvasHash(Integer.toHexString(finalXml.hashCode()))
                        .build())
                .initialCanvasXml(initialXml)
                .finalCanvasXml(finalXml)
                .gitSha(gitSha)
                .executionProfileHash(profileHash(profile))
                .promptConfigHash(profile == null ? null : profile.getPromptConfigHash())
                .skillCatalogHash(profile == null ? null : profile.getSkillCatalogHash())
                .toolPolicyVersion(profile == null ? null : profile.getToolPolicyVersion())
                .build();
    }

    private EvalExecution createMultiTurn(EvalCaseDefinition evalCase, EvalCaseDefinition.Replay replay, String initialXml) throws Exception {
        List<String> users = userTurns(evalCase);
        if (users.size() != replay.getTurns().size()) {
            throw new IllegalArgumentException("input.turns and replay.turns must have the same size");
        }
        String currentXml = initialXml;
        List<EvalExecution.TurnExecution> turns = new ArrayList<>();
        List<EvalTrace.ToolCall> allCalls = new ArrayList<>();
        EvalTrace lastTrace = null;
        for (int index = 0; index < replay.getTurns().size(); index++) {
            EvalCaseDefinition.ReplayTurn replayTurn = replay.getTurns().get(index);
            String before = currentXml;
            IntentRoutingResult routing = route(users.get(index), before,
                    require(replayTurn.getRouterReply(), "replay.turns[].routerReply"));
            List<EvalTrace.ToolCall> calls = new ArrayList<>();
            List<EvalCaseDefinition.ReplayToolCall> recordedCalls = replayTurn.getToolCalls() == null
                    ? List.of() : replayTurn.getToolCalls();
            if (!recordedCalls.isEmpty()) {
                for (String required : evalCase.getExpected().getRequiredToolNamesBeforeMutation()) calls.add(successfulCall(required));
            }
            for (EvalCaseDefinition.ReplayToolCall call : recordedCalls) {
                currentXml = executeTool(call, currentXml);
                calls.add(successfulCall(call.getName()));
            }
            allCalls.addAll(calls);
            lastTrace = trace(routing, replayTurn.getTaskOutcome(), calls, before, currentXml);
            turns.add(EvalExecution.TurnExecution.builder().index(index).user(users.get(index)).trace(lastTrace)
                    .beforeCanvasXml(before).afterCanvasXml(currentXml).build());
        }
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        EvalTrace aggregate = EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(lastTrace == null ? EvalTrace.TaskOutcome.UNKNOWN : lastTrace.getTaskOutcome())
                .routing(lastTrace == null ? null : lastTrace.getRouting()).toolCalls(allCalls)
                .beforeCanvasHash(Integer.toHexString(initialXml.hashCode()))
                .afterCanvasHash(Integer.toHexString(currentXml.hashCode())).build();
        return EvalExecution.builder().evalCase(evalCase).trace(aggregate).turns(turns)
                .initialCanvasXml(initialXml).finalCanvasXml(currentXml).gitSha(gitSha)
                .executionProfileHash(profileHash(profile))
                .promptConfigHash(profile == null ? null : profile.getPromptConfigHash())
                .skillCatalogHash(profile == null ? null : profile.getSkillCatalogHash())
                .toolPolicyVersion(profile == null ? null : profile.getToolPolicyVersion()).build();
    }

    private EvalTrace trace(IntentRoutingResult routing, EvalTrace.TaskOutcome outcome, List<EvalTrace.ToolCall> calls,
                            String before, String after) {
        return EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(outcome == null ? EvalTrace.TaskOutcome.UNKNOWN : outcome)
                .routing(EvalTrace.Routing.builder().routeType(routing.getRouteType()).diagramType(routing.getDiagramType())
                        .skillName(routing.getSkillName()).needsCanvasQuality(routing.getNeedsCanvasQuality())
                        .needsSemanticReview(routing.getNeedsSemanticReview()).answerMode(routing.getAnswerMode()).build())
                .toolCalls(calls).beforeCanvasHash(Integer.toHexString(before.hashCode()))
                .afterCanvasHash(Integer.toHexString(after.hashCode())).build();
    }

    private IntentRoutingResult route(String userMessage, String canvasXml, String reply) throws Exception {
        DefaultIntentRoutingService service = new DefaultIntentRoutingService();
        inject(service, "chatService", new RecordedReplyChatService(reply));
        inject(service, "skillCatalogService", new EmptySkillCatalogService());
        return service.route(IntentRoutingCommand.builder()
                .userId("eval-user")
                .message(userMessage)
                .canvasXml(canvasXml)
                .build());
    }

    private String executeTool(EvalCaseDefinition.ReplayToolCall call, String initialXml) {
        DrawioCanvasMcpService service = new DrawioCanvasMcpService();
        String toolName = require(call.getName(), "replay.toolCalls[].name");
        if (DrawioCanvasToolNames.MODIFY_DIAGRAM.equals(toolName)) {
            DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
            request.setMode(require(call.getMode(), "replay.toolCalls[].mode"));
            request.setXml(call.getXml() == null ? initialXml : call.getXml());
            request.setCells(require(call.getCells(), "replay.toolCalls[].cells"));
            DrawioCanvasMcpService.DrawioMutationResponse response = service.modifyDiagram(request);
            assertRepairSignal(call, response.getRepairBrief());
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
            request.setXml(require(call.getXml() == null ? call.getCells() : call.getXml(),
                    "replay.toolCalls[].xml or cells"));
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

    private List<String> userTurns(EvalCaseDefinition evalCase) {
        Object turns = evalCase.getInput().get("turns");
        if (!(turns instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("input.turns is required for multi-turn replay");
        }
        return values.stream().map(value -> {
            if (value instanceof java.util.Map<?, ?> map) {
                Object user = map.containsKey("user") ? map.get("user") : map.get("message");
                return String.valueOf(user);
            }
            return String.valueOf(value);
        }).toList();
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

    private String profileHash(EvalCaseDefinition.ExecutionProfile profile) {
        return profile == null ? null : Integer.toHexString((profile.getProfileId() + "|" + profile.getModel()
                + "|" + profile.getPromptConfigHash() + "|" + profile.getSkillCatalogHash()
                + "|" + profile.getToolPolicyVersion()).hashCode());
    }

    private void assertRepairSignal(EvalCaseDefinition.ReplayToolCall call, String repairBrief) {
        String expected = call.getExpectedRepairContains();
        if (expected != null && (repairBrief == null || !repairBrief.contains(expected))) {
            throw new IllegalStateException("Expected repair signal containing: " + expected);
        }
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
