package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        if (input == null) return unavailable("Judge input is missing.");
        try {
            String sessionId = chatService.createSession(agentId, "eval-judge-system");
            List<String> replies = chatService.handleMessage(agentId, "eval-judge-system", sessionId, prompt(input));
            if (replies == null || replies.isEmpty()) return unavailable("Judge returned no output.");
            return parse(replies.get(replies.size() - 1));
        } catch (Exception e) {
            return unavailable("Judge invocation or schema validation failed.");
        }
    }

    private String prompt(JudgeInput input) throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("case_id", input.caseId());
        evidence.put("diagram_type", input.diagramType());
        evidence.put("user_task", input.userTask());
        evidence.put("normalized_graph", input.normalizedGraph());
        evidence.put("assistant_response", input.responseText());
        return "You are an evaluation Judge. Assess only observable output; never infer hidden reasoning. "
                + "Return exactly one JSON object with no markdown and exactly these fields: "
                + "task_fulfilled(boolean), semantic_score(integer 1-5), visual_score(integer 1-5), "
                + "unexpected_side_effect(boolean), severity(one of none/minor/major/critical), "
                + "evidence(array of 1-10 concise strings), recommended_human_review(boolean). "
                + "A critical issue is unusable/corrupt/unsafe output; major means the core task is materially wrong or incomplete. "
                + "Prompt=" + PROMPT_VERSION + ", rubric=" + RUBRIC_VERSION + ", schema=" + SCHEMA_VERSION + ".\n"
                + mapper.writeValueAsString(evidence);
    }

    private EvalJudgeResult parse(String output) throws Exception {
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
                .confidence(humanReview ? "low" : "high").judgeVersion(version()).evidence(evidence).build();
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

    private EvalJudgeResult unavailable(String reason) {
        return EvalJudgeResult.builder().available(false).passed(false).judgeVersion(version())
                .evidence(List.of(reason)).build();
    }

    public String version() {
        return "chat-agent:" + agentId + ":" + PROMPT_VERSION + ":" + RUBRIC_VERSION + ":" + SCHEMA_VERSION;
    }
}
