package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Production semantic Judge. It accepts only the versioned JSON contract and fails closed as UNAVAILABLE. */
@Service
@Slf4j
public class ChatEvalJudge implements IEvalJudge {
    private static final String PROMPT_VERSION = "eval-judge-prompt-v1";
    private static final String RUBRIC_VERSION = "eval-judge-rubric-v1";
    private static final String SCHEMA_VERSION = "eval-judge-schema-v1";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "task_fulfilled", "semantic_score", "visual_score", "unexpected_side_effect",
            "severity", "evidence", "recommended_human_review");
    private static final Set<String> SEVERITIES = Set.of("none", "minor", "major", "critical");

    private final IChatService chatService;
    private final String agentId;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatEvalJudge(IChatService chatService,
                         @Value("${zipp.evaluation.judge-agent-id:300014}") String agentId) {
        this.chatService = chatService;
        this.agentId = agentId;
    }

    @Override
    public EvalJudgeResult judge(JudgeInput input) {
        if (input == null) return unavailable("input_missing", null);
        if (input.evidenceVersion() == null) return unavailable("input_version_missing", input);
        if (requiresVisualEvidence(input) && (input.renderEvidence() == null || !input.renderEvidence().isComplete())) {
            return unavailable("visual_evidence_unavailable", input);
        }
        String prompt;
        try {
            prompt = prompt(input);
        } catch (Exception e) {
            unavailableLog("input_serialization_error", e);
            return unavailable("input_serialization_error", input);
        }
        List<String> replies;
        try {
            String sessionId = chatService.createSession(agentId, "eval-judge-system");
            replies = chatService.handleMessage(agentId, "eval-judge-system", sessionId, prompt);
        } catch (RuntimeException e) {
            unavailableLog("provider_error", e);
            return unavailable("provider_error", input);
        }
        if (replies == null || replies.isEmpty()) return unavailable("empty_output", input);
        try {
            return parse(replies.get(replies.size() - 1), input);
        } catch (Exception e) {
            unavailableLog("output_schema_error", e);
            return unavailable("output_schema_error", input);
        }
    }

    private String prompt(JudgeInput input) throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("case_id", input.caseId());
        evidence.put("diagram_type", input.diagramType());
        evidence.put("user_task", input.userTask());
        evidence.put("initial_graph", input.initialGraph());
        evidence.put("final_graph", input.finalGraph());
        evidence.put("assistant_response", input.responseText());
        evidence.put("deterministic_issues", input.deterministicIssues());
        evidence.put("tool_trace_summary", input.toolTraceSummary());
        evidence.put("render_evidence", input.renderEvidence());
        evidence.put("evidence_version", input.evidenceVersion());
        return "You are an evaluation Judge. Assess only observable output; never infer hidden reasoning. "
                + "Return exactly one JSON object with no markdown and exactly these fields: "
                + "task_fulfilled(boolean), semantic_score(integer 1-5), visual_score(integer 1-5), "
                + "unexpected_side_effect(boolean), severity(one of none/minor/major/critical), "
                + "evidence(array of 1-10 concise strings), recommended_human_review(boolean). "
                + "A critical issue is unusable/corrupt/unsafe output; major means the core task is materially wrong or incomplete. "
                + "Prompt=" + PROMPT_VERSION + ", rubric=" + input.evidenceVersion().rubricVersion()
                + ", renderer=" + input.evidenceVersion().inputRendererVersion() + ", schema=" + SCHEMA_VERSION + ".\n"
                + mapper.writeValueAsString(evidence);
    }

    private EvalJudgeResult parse(String output, JudgeInput input) throws Exception {
        JsonNode root = mapper.readTree(output);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Judge output must be an object");
        validateExactFields(root);
        boolean fulfilled = requiredBoolean(root, "task_fulfilled");
        int semantic = requiredScore(root, "semantic_score");
        int visual = requiredScore(root, "visual_score");
        boolean sideEffect = requiredBoolean(root, "unexpected_side_effect");
        String severity = requiredText(root, "severity");
        if (!SEVERITIES.contains(severity)) throw new IllegalArgumentException("Invalid severity");
        boolean humanReview = requiredBoolean(root, "recommended_human_review");
        List<String> evidence = evidence(root.path("evidence"));
        boolean passed = fulfilled && !sideEffect && !"major".equals(severity) && !"critical".equals(severity)
                && semantic >= 3 && visual >= 3;
        return EvalJudgeResult.builder().available(true).passed(passed).score((semantic + visual) / 2D)
                .criticalIssues("critical".equals(severity) ? 1 : 0)
                .majorIssues("major".equals(severity) || sideEffect ? 1 : 0)
                .confidence(humanReview ? "low" : "high").judgeVersion(version(input)).evidence(evidence).build();
    }

    private void validateExactFields(JsonNode root) {
        Set<String> actual = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!ROOT_FIELDS.equals(actual)) throw new IllegalArgumentException("Judge output fields do not match schema");
    }

    private boolean requiredBoolean(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("Invalid " + field);
        return value.booleanValue();
    }

    private int requiredScore(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || value.intValue() < 1 || value.intValue() > 5) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.intValue();
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || StringUtils.isBlank(value.textValue())) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value.textValue();
    }

    private List<String> evidence(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > 10) throw new IllegalArgumentException("Invalid evidence");
        List<String> result = new ArrayList<>();
        Iterator<JsonNode> values = node.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (!value.isTextual() || StringUtils.isBlank(value.textValue())) throw new IllegalArgumentException("Invalid evidence item");
            result.add(StringUtils.left(value.textValue(), 500));
        }
        return result;
    }

    private boolean requiresVisualEvidence(JudgeInput input) {
        return StringUtils.isNotBlank(input.diagramType()) && !"none".equalsIgnoreCase(input.diagramType());
    }

    private EvalJudgeResult unavailable(String category, JudgeInput input) {
        return EvalJudgeResult.builder().available(false).passed(false).judgeVersion(version(input))
                .evidence(List.of("judge_unavailable:" + category)).build();
    }

    private void unavailableLog(String category, Exception error) {
        // Never log prompt/model output; the safe category and exception class are enough for operations.
        log.warn("[eval-judge] unavailable category={} errorClass={}", category, error.getClass().getSimpleName());
    }

    public String version(JudgeInput input) {
        EvidenceVersion evidence = input == null ? null : input.evidenceVersion();
        String model = evidence == null ? "unknown-model" : StringUtils.defaultIfBlank(evidence.model(), "unknown-model");
        String temperature = evidence == null || evidence.temperature() == null ? "unknown-temperature" : evidence.temperature().toString();
        String renderer = evidence == null ? "unknown-renderer" : StringUtils.defaultIfBlank(evidence.inputRendererVersion(), "unknown-renderer");
        String rubric = evidence == null ? RUBRIC_VERSION : StringUtils.defaultIfBlank(evidence.rubricVersion(), RUBRIC_VERSION);
        return "chat-agent:" + agentId + ":model=" + model + ":temperature=" + temperature
                + ":" + PROMPT_VERSION + ":" + rubric + ":" + renderer + ":" + SCHEMA_VERSION;
    }
}
