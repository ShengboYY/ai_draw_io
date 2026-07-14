package org.zipp.ai.domain.agent.service.evaluation.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalJudgeResult;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Multimodal diagram Judge with an exact schema; pixels are sent inline and never as a URL or artifact reference. */
@Service
public class ChatVisualEvalJudge implements IVisualEvalJudge {
    private static final String PROMPT_VERSION = "visual-judge-prompt-v1";
    private static final String RUBRIC_VERSION = "visual-judge-rubric-v1";
    private static final String SCHEMA_VERSION = "visual-judge-schema-v1";
    private static final Set<String> FIELDS = Set.of("taskFulfilled", "readabilityScore", "layoutScore",
            "criticalIssues", "majorIssues", "evidence", "recommendedHumanReview");
    private final IChatService chat;
    private final String agentId;
    private final String modelVersion;
    private final double temperature;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatVisualEvalJudge(IChatService chat,
            @Value("${zipp.evaluation.visual-judge-agent-id:300016}") String agentId,
            @Value("${zipp.evaluation.visual-judge-model-version:unconfigured}") String modelVersion,
            @Value("${zipp.evaluation.visual-judge-temperature:0}") double temperature) {
        this.chat = chat; this.agentId = agentId; this.modelVersion = modelVersion; this.temperature = temperature;
    }

    @Override public EvalJudgeResult judge(VisualJudgeInput input) {
        if (input == null || input.before() == null || input.after() == null || unconfigured()) return unavailable("input_or_model_unavailable");
        try {
            String session = chat.createSession(agentId, "visual-judge-system");
            ChatCommandEntity command = ChatCommandEntity.builder().agentId(agentId).userId("visual-judge-system")
                    .sessionId(session).texts(List.of(new ChatCommandEntity.Content.Text(prompt(input))))
                    .files(List.of()).inlineDatas(List.of(inline(input.before()), inline(input.after()))).build();
            List<String> replies = chat.handleMessage(command);
            if (replies == null || replies.isEmpty()) return unavailable("empty_output");
            return parse(replies.get(replies.size() - 1));
        } catch (RuntimeException e) {
            return unavailable("provider_or_schema_error");
        }
    }

    @Override public String version() {
        return "visual-agent:" + agentId + ":model=" + modelVersion + ":temperature=" + temperature
                + ":" + PROMPT_VERSION + ":" + RUBRIC_VERSION + ":" + SCHEMA_VERSION;
    }

    private String prompt(VisualJudgeInput input) {
        return "Compare the first image (before) with the second image (after). Judge only visible diagram quality and task fulfillment. "
                + "Return JSON only with exactly taskFulfilled(boolean), readabilityScore(integer 1-5), layoutScore(integer 1-5), "
                + "criticalIssues(integer >=0), majorIssues(integer >=0), evidence(array 1-8 strings), recommendedHumanReview(boolean). "
                + "Do not infer hidden reasoning or reproduce image text that is unrelated to evidence. "
                + "case=" + safe(input.caseId()) + "; diagramType=" + safe(input.diagramType()) + "; task=" + safe(input.userTask())
                + "; analyzerEvidence=" + input.analyzerEvidence() + "; renderer=" + input.after().rendererVersion();
    }

    private EvalJudgeResult parse(String output) {
        try {
            JsonNode root = mapper.readTree(output); Set<String> fields = new HashSet<>(); root.fieldNames().forEachRemaining(fields::add);
            if (!root.isObject() || !FIELDS.equals(fields)) throw new IllegalArgumentException("invalid visual Judge fields");
            boolean fulfilled = bool(root, "taskFulfilled"); int readability = score(root, "readabilityScore"); int layout = score(root, "layoutScore");
            int critical = count(root, "criticalIssues"); int major = count(root, "majorIssues"); boolean review = bool(root, "recommendedHumanReview");
            List<String> evidence = evidence(root.path("evidence"));
            boolean passed = fulfilled && readability >= 3 && layout >= 3 && critical == 0 && major == 0;
            return EvalJudgeResult.builder().available(true).passed(passed).score((readability + layout) / 2D)
                    .criticalIssues(critical).majorIssues(major).confidence(review ? "low" : "high")
                    .judgeVersion(version()).evidence(evidence).build();
        } catch (Exception e) { throw new IllegalArgumentException("invalid visual Judge output", e); }
    }

    private ChatCommandEntity.Content.InlineData inline(IDiagramImageRenderer.RenderedDiagram image) { return new ChatCommandEntity.Content.InlineData(image.bytes(), image.mimeType()); }
    private boolean bool(JsonNode root, String field) { if (!root.path(field).isBoolean()) throw new IllegalArgumentException(field); return root.path(field).asBoolean(); }
    private int score(JsonNode root, String field) { int value = count(root, field); if (value < 1 || value > 5) throw new IllegalArgumentException(field); return value; }
    private int count(JsonNode root, String field) { if (!root.path(field).isIntegralNumber() || root.path(field).asInt() < 0) throw new IllegalArgumentException(field); return root.path(field).asInt(); }
    private List<String> evidence(JsonNode node) { if (!node.isArray() || node.isEmpty() || node.size() > 8) throw new IllegalArgumentException("evidence"); List<String> result = new ArrayList<>(); node.forEach(value -> { if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("evidence"); result.add(StringUtils.left(value.asText(), 300)); }); return result; }
    private EvalJudgeResult unavailable(String reason) { return EvalJudgeResult.builder().available(false).passed(false).judgeVersion(version()).evidence(List.of("visual_judge_unavailable:" + reason)).build(); }
    private boolean unconfigured() { return StringUtils.isBlank(modelVersion) || "unconfigured".equalsIgnoreCase(modelVersion); }
    private String safe(String value) { return StringUtils.left(StringUtils.defaultString(value).replaceAll("[\\r\\n]", " "), 1000); }
}
