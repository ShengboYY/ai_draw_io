package org.zipp.ai.trigger.evaluation;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;

import java.util.List;
import java.util.Map;

/** Mode C adapter for the production 300018 reviewer; it never calls the evaluation-only 300016 agent. */
@Component
public class ProductionVisualReviewLiveEvalAdapter implements LiveEvalRunner.LiveExecutionFactory {
    private static final String EMPTY_CANVAS =
            "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";

    private final ICanvasVisualReviewer reviewer;
    private final String runtimeModelVersion;
    private final double runtimeTemperature;
    private final String gitSha;
    private final CanvasVisualReviewPolicy policy = new CanvasVisualReviewPolicy();
    private final CanvasXmlContentHasher hasher = new CanvasXmlContentHasher();
    private final SyntheticVisualReviewFixtureRenderer fixtureRenderer = new SyntheticVisualReviewFixtureRenderer();

    public ProductionVisualReviewLiveEvalAdapter(
            ICanvasVisualReviewer reviewer,
            @Value("${zipp.visual-review.model-version:${VLM_MODEL:unconfigured}}") String runtimeModelVersion,
            @Value("${VLM_TEMPERATURE:1}") double runtimeTemperature,
            @Value("${GIT_SHA:unknown}") String gitSha) {
        this.reviewer = reviewer;
        this.runtimeModelVersion = runtimeModelVersion;
        this.runtimeTemperature = runtimeTemperature;
        this.gitSha = gitSha;
    }

    @Override
    public EvalExecution execute(EvalCaseDefinition evalCase) {
        verifyFrozenModel(evalCase);
        Map<String, Object> input = evalCase.getInput();
        SyntheticVisualReviewFixtureRenderer.Images fixtureImages = fixtureImages(input);
        String afterImage = StringUtils.defaultIfBlank(optionalText(input, "afterImageDataUrl"),
                fixtureImages == null ? null : fixtureImages.afterImageDataUrl());
        String beforeImage = StringUtils.defaultIfBlank(optionalText(input, "beforeImageDataUrl"),
                fixtureImages == null ? null : fixtureImages.beforeImageDataUrl());
        if (StringUtils.isBlank(afterImage)) {
            throw new IllegalArgumentException("Visual Review Evaluation Case requires input.afterImageDataUrl or input.syntheticFixture");
        }
        CanvasVisualReviewStage stage = stage(input);
        CanvasVisualReviewCommand command = CanvasVisualReviewCommand.builder()
                .stage(stage)
                .originalUserTask(requiredText(input, "user"))
                .diagramType(StringUtils.defaultIfBlank(evalCase.getDiagramType(), "none"))
                .beforeImageDataUrl(beforeImage)
                .afterImageDataUrl(afterImage)
                .analyzerEvidence(stringList(input.get("analyzerEvidence")))
                .canvasSummary(optionalText(input, "canvasSummary"))
                .languageHint(optionalText(input, "languageHint"))
                .rendererVersion(StringUtils.defaultIfBlank(optionalText(input, "rendererVersion"), "eval-png-v1"))
                .build();
        CanvasVisualReviewResult result = injectedFailure(input);
        if (result == null) {
            result = reviewer.review(command);
        }
        CanvasVisualReviewDecision decision = policy.decide(
                result, stage, switch (stage) {
                    case POST_REPAIR -> 1;
                    case VERIFY_ONLY -> CanvasVisualReviewPolicy.MAX_AUTOMATIC_REPAIR_ROUNDS;
                    default -> 0;
                });
        String initialXml = evalCase.getReplay() == null ? EMPTY_CANVAS
                : StringUtils.defaultIfBlank(evalCase.getReplay().getInitialCanvasXml(), EMPTY_CANVAS);
        EvalTrace trace = EvalTrace.builder()
                .runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(result != null && result.isAvailable()
                        ? EvalTrace.TaskOutcome.FULFILLED : EvalTrace.TaskOutcome.UNKNOWN)
                .visualReview(project(result, decision))
                .steps(List.of(EvalTrace.Step.builder().phase("visual_review").agentId(reviewer.agentId())
                        .status(EvalTrace.RunStatus.SUCCESS).build()))
                .beforeCanvasHash(hasher.hash(initialXml))
                .afterCanvasHash(hasher.hash(initialXml))
                .build();
        return EvalExecution.builder()
                .evalCase(evalCase)
                .trace(trace)
                .initialCanvasXml(initialXml)
                .finalCanvasXml(initialXml)
                .responseText(response(result, decision))
                .gitSha(gitSha)
                .build();
    }

    private EvalTrace.VisualReview project(CanvasVisualReviewResult result,
                                           CanvasVisualReviewDecision decision) {
        List<CanvasVisualIssue> issues = result == null ? List.of() : result.safeIssues();
        return EvalTrace.VisualReview.builder()
                .available(result != null && result.isAvailable())
                .decision(decision.name())
                .unavailableReason(result == null ? "missing_result" : result.getUnavailableReason())
                .recommendedHumanReview(result != null && result.isRecommendedHumanReview())
                .reviewerVersion(result == null ? reviewer.version()
                        : StringUtils.defaultIfBlank(result.getReviewerVersion(), reviewer.version()))
                .issueTypes(issues.stream().map(CanvasVisualIssue::getType).filter(java.util.Objects::nonNull)
                        .map(Enum::name).toList())
                .issueSeverities(issues.stream().map(CanvasVisualIssue::getSeverity).filter(java.util.Objects::nonNull)
                        .map(Enum::name).toList())
                .build();
    }

    private String response(CanvasVisualReviewResult result, CanvasVisualReviewDecision decision) {
        JSONObject value = new JSONObject();
        value.put("available", result != null && result.isAvailable());
        value.put("decision", decision.name());
        value.put("summary", result == null ? "" : StringUtils.defaultString(result.getSummary()));
        value.put("recommendedHumanReview", result != null && result.isRecommendedHumanReview());
        value.put("unavailableReason", result == null ? "missing_result"
                : StringUtils.defaultString(result.getUnavailableReason()));
        JSONArray issues = new JSONArray();
        if (result != null) {
            result.safeIssues().stream().limit(5).map(CanvasVisualIssue::boundedCopy).forEach(issue -> {
                JSONObject item = new JSONObject();
                item.put("type", issue.getType() == null ? "" : issue.getType().name());
                item.put("severity", issue.getSeverity() == null ? "" : issue.getSeverity().name());
                item.put("region", issue.getRegion());
                item.put("evidence", issue.getEvidence());
                issues.add(item);
            });
        }
        value.put("issues", issues);
        return value.toJSONString();
    }

    private CanvasVisualReviewStage stage(Map<String, Object> input) {
        String value = StringUtils.defaultIfBlank(optionalText(input, "stage"), "CURRENT_CANVAS");
        try {
            return CanvasVisualReviewStage.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Visual Review Evaluation Case has invalid input.stage", error);
        }
    }

    private SyntheticVisualReviewFixtureRenderer.Images fixtureImages(Map<String, Object> input) {
        String fixture = optionalText(input, "syntheticFixture");
        return StringUtils.isBlank(fixture) ? null : fixtureRenderer.render(fixture);
    }

    private CanvasVisualReviewResult injectedFailure(Map<String, Object> input) {
        // Fault injection is evaluation-only and makes timeout/schema cases deterministic without
        // routing product traffic to the evaluation reviewer.
        return switch (StringUtils.defaultString(optionalText(input, "failureInjection"))) {
            case "provider_timeout" -> CanvasVisualReviewResult.unavailable("timeout");
            case "schema_malformed" -> CanvasVisualReviewResult.unavailable("output_schema_error");
            case "" -> null;
            default -> throw new IllegalArgumentException("Unknown visual review failureInjection");
        };
    }

    private String requiredText(Map<String, Object> input, String key) {
        String value = optionalText(input, key);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("Visual Review Evaluation Case requires input." + key);
        }
        return value;
    }

    private String optionalText(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }

    private void verifyFrozenModel(EvalCaseDefinition evalCase) {
        EvalCaseDefinition.ExecutionProfile profile = evalCase.getExecutionProfile();
        if (profile != null && StringUtils.isNotBlank(profile.getModel())
                && !profile.getModel().equals(runtimeModelVersion)) {
            throw new IllegalStateException("visual reviewer runtime model does not match the frozen Evaluation Profile");
        }
        if (profile != null && profile.getTemperature() != null
                && Double.compare(profile.getTemperature(), runtimeTemperature) != 0) {
            throw new IllegalStateException("visual reviewer runtime temperature does not match the frozen Evaluation Profile");
        }
    }
}
