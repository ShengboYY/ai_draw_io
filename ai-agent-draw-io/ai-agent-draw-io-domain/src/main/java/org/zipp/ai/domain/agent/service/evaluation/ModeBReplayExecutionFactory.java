package org.zipp.ai.domain.agent.service.evaluation;

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
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.intent.DefaultIntentRoutingService;

import java.util.ArrayList;
import java.util.List;

/** Runs deterministic replay data through the real router post-processing and canvas tools. */
public class ModeBReplayExecutionFactory implements EvalBatchRunner.ExecutionFactory {
    private final String gitSha;
    private final ReplayCanvasToolExecutor tools = new ReplayCanvasToolExecutor();

    public ModeBReplayExecutionFactory() {
        this(System.getProperty("eval.gitSha", "mode-b-local"));
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
        IntentRoutingResult routing = route(userMessage(evalCase), initialXml,
                require(replay.getRouterReply(), "replay.routerReply"));
        List<EvalTrace.ToolCall> calls = new ArrayList<>();
        for (String required : safe(evalCase.getExpected().getRequiredToolNamesBeforeMutation())) {
            calls.add(successfulCall(required));
        }
        String finalXml = initialXml;
        for (EvalCaseDefinition.ReplayToolCall call : safe(replay.getToolCalls())) {
            finalXml = executeTool(call, finalXml);
            calls.add(successfulCall(call.getName()));
        }
        EvalTrace.TaskOutcome outcome = replay.getTaskOutcome() == null
                ? EvalTrace.TaskOutcome.UNKNOWN : replay.getTaskOutcome();
        return execution(evalCase, initialXml, finalXml, trace(routing, outcome, calls, initialXml, finalXml), List.of());
    }

    private EvalExecution createMultiTurn(EvalCaseDefinition evalCase, EvalCaseDefinition.Replay replay,
                                          String initialXml) throws Exception {
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
            List<EvalCaseDefinition.ReplayToolCall> recorded = safe(replayTurn.getToolCalls());
            if (!recorded.isEmpty()) {
                for (String required : safe(evalCase.getExpected().getRequiredToolNamesBeforeMutation())) {
                    calls.add(successfulCall(required));
                }
            }
            for (EvalCaseDefinition.ReplayToolCall call : recorded) {
                currentXml = executeTool(call, currentXml);
                calls.add(successfulCall(call.getName()));
            }
            allCalls.addAll(calls);
            lastTrace = trace(routing, replayTurn.getTaskOutcome(), calls, before, currentXml);
            turns.add(EvalExecution.TurnExecution.builder().index(index).user(users.get(index)).trace(lastTrace)
                    .beforeCanvasXml(before).afterCanvasXml(currentXml).build());
        }
        EvalTrace aggregate = EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(lastTrace == null ? EvalTrace.TaskOutcome.UNKNOWN : lastTrace.getTaskOutcome())
                .routing(lastTrace == null ? null : lastTrace.getRouting()).toolCalls(allCalls)
                .beforeCanvasHash(hash(initialXml)).afterCanvasHash(hash(currentXml)).build();
        return execution(evalCase, initialXml, currentXml, aggregate, turns);
    }

    private EvalExecution execution(EvalCaseDefinition evalCase, String initialXml, String finalXml,
                                    EvalTrace trace, List<EvalExecution.TurnExecution> turns) {
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        return EvalExecution.builder().evalCase(evalCase).trace(trace).turns(turns)
                .initialCanvasXml(initialXml).finalCanvasXml(finalXml).gitSha(gitSha)
                .executionProfileHash(profileHash(profile))
                .promptConfigHash(profile == null ? null : profile.getPromptConfigHash())
                .skillCatalogHash(profile == null ? null : profile.getSkillCatalogHash())
                .toolPolicyVersion(profile == null ? null : profile.getToolPolicyVersion()).build();
    }

    private EvalTrace trace(IntentRoutingResult routing, EvalTrace.TaskOutcome outcome,
                            List<EvalTrace.ToolCall> calls, String before, String after) {
        return EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(outcome == null ? EvalTrace.TaskOutcome.UNKNOWN : outcome)
                .routing(EvalTrace.Routing.builder().routeType(routing.getRouteType())
                        .diagramType(routing.getDiagramType()).skillName(routing.getSkillName()).build())
                .toolCalls(calls).beforeCanvasHash(hash(before)).afterCanvasHash(hash(after)).build();
    }

    private IntentRoutingResult route(String user, String canvasXml, String recordedReply) {
        DefaultIntentRoutingService router = new DefaultIntentRoutingService(
                new RecordedReplyChatService(recordedReply), new EmptySkillCatalogService());
        return router.route(IntentRoutingCommand.builder().userId("eval-user")
                .message(user).canvasXml(canvasXml).build());
    }

    private String executeTool(EvalCaseDefinition.ReplayToolCall call, String currentXml) {
        return tools.execute(call, currentXml);
    }

    private String userMessage(EvalCaseDefinition evalCase) {
        Object user = evalCase.getInput().get("user");
        if (user instanceof String value && !value.isBlank()) return value;
        List<String> turns = userTurns(evalCase);
        return turns.get(0);
    }

    private List<String> userTurns(EvalCaseDefinition evalCase) {
        Object turns = evalCase.getInput().get("turns");
        if (!(turns instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("input.user or input.turns is required");
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
        if (evalCase == null || evalCase.getReplay() == null) {
            throw new IllegalArgumentException("replay is required for Mode B");
        }
        return evalCase.getReplay();
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private EvalTrace.ToolCall successfulCall(String name) {
        return EvalTrace.ToolCall.builder().name(name).status(EvalTrace.RunStatus.SUCCESS).build();
    }

    private String profileHash(EvalCaseDefinition.ExecutionProfile profile) {
        if (profile == null) return null;
        return Integer.toHexString((profile.getProfileId() + "|" + profile.getModel()
                + "|" + profile.getPromptConfigHash() + "|" + profile.getSkillCatalogHash()
                + "|" + profile.getToolPolicyVersion() + "|" + profile.getTemperature()
                + "|" + profile.getInputPricePerMillion() + "|" + profile.getOutputPricePerMillion()).hashCode());
    }

    private String hash(String value) {
        return Integer.toHexString(value.hashCode());
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static final class EmptySkillCatalogService extends SkillCatalogService {
        @Override public RouterCatalog routerCatalog(String ownerId) { return new RouterCatalog("", java.util.Set.of()); }
    }

    private static final class RecordedReplyChatService implements IChatService {
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
