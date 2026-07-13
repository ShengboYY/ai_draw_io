package org.zipp.ai.trigger.evaluation;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;

/** Production Mode C router adapter; it never invokes the drawing agent or canvas tools. */
@Component
public class ProductionRouterLiveEvalAdapter implements LiveEvalRunner.LiveExecutionFactory {
    private static final String EMPTY_CANVAS = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
    private final IIntentRoutingService router;
    private final String runtimeModelVersion;
    private final String gitSha;
    private final CanvasXmlContentHasher hasher = new CanvasXmlContentHasher();

    public ProductionRouterLiveEvalAdapter(IIntentRoutingService router,
            @Value("${zipp.evaluation.live-model-version:gpt-5.5}") String runtimeModelVersion,
            @Value("${GIT_SHA:unknown}") String gitSha) {
        this.router = router; this.runtimeModelVersion = runtimeModelVersion; this.gitSha = gitSha;
    }

    @Override
    public EvalExecution execute(EvalCaseDefinition evalCase) {
        verifyFrozenModel(evalCase);
        String message = userMessage(evalCase);
        String initialXml = evalCase.getReplay() == null
                ? EMPTY_CANVAS : StringUtils.defaultIfBlank(evalCase.getReplay().getInitialCanvasXml(), EMPTY_CANVAS);
        IntentRoutingResult result = router.route(IntentRoutingCommand.builder().userId("eval-router-system")
                .message(message).canvasXml(initialXml).canvasSummary(canvasSummary(evalCase)).build());
        EvalTrace trace = EvalTrace.builder().runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(outcome(result.getRouteType()))
                .routing(EvalTrace.Routing.builder().routeType(result.getRouteType()).diagramType(result.getDiagramType())
                        .skillName(result.getSkillName()).needsCanvasQuality(result.getNeedsCanvasQuality())
                        .needsSemanticReview(result.getNeedsSemanticReview()).answerMode(result.getAnswerMode()).build())
                .beforeCanvasHash(hasher.hash(initialXml)).afterCanvasHash(hasher.hash(initialXml)).build();
        return EvalExecution.builder().evalCase(evalCase).trace(trace).initialCanvasXml(initialXml)
                .finalCanvasXml(initialXml).responseText(result.getAnswer()).gitSha(gitSha).build();
    }

    private EvalTrace.TaskOutcome outcome(String routeType) {
        return "clarify".equals(routeType)
                ? EvalTrace.TaskOutcome.CLARIFICATION_NEEDED : EvalTrace.TaskOutcome.FULFILLED;
    }

    private String userMessage(EvalCaseDefinition evalCase) {
        Object user = evalCase.getInput().get("user");
        if (user instanceof String value && StringUtils.isNotBlank(value)) return value;
        Object turns = evalCase.getInput().get("turns");
        if (turns instanceof java.util.List<?> values && !values.isEmpty()) return String.valueOf(values.get(0));
        throw new IllegalArgumentException("Router Evaluation Case requires input.user or input.turns");
    }

    private String canvasSummary(EvalCaseDefinition evalCase) {
        Object summary = evalCase.getInput().get("canvasSummary");
        return summary == null ? null : String.valueOf(summary);
    }

    private void verifyFrozenModel(EvalCaseDefinition evalCase) {
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        if (profile != null && StringUtils.isNotBlank(profile.getModel())
                && !profile.getModel().equals(runtimeModelVersion)) {
            throw new IllegalStateException("router runtime model does not match the frozen Evaluation Profile");
        }
    }
}
