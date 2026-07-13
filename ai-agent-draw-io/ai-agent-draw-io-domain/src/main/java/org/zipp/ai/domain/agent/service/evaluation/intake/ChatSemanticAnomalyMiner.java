package org.zipp.ai.domain.agent.service.evaluation.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/** Dedicated model adapter with a closed schema; it cannot emit workflow decisions. */
@Service
public class ChatSemanticAnomalyMiner implements ISemanticAnomalyMiner {
    private static final Set<String> KEYS = Set.of("isPotentialAnomaly", "confidence", "failureFamily",
            "evidence", "suggestedRisk", "requiresHumanReview");
    private static final Set<String> FAMILIES = Set.of("FALSE_SUCCESS", "INTENT_MISMATCH",
            "CLARIFICATION_FAILURE", "TRAJECTORY_WASTE", "NONE");
    private static final Set<String> RISKS = Set.of("critical", "high", "medium", "low");
    private static final Pattern PRODUCTION_ID = Pattern.compile("(?i)\\b(?:run|aru|usr|ecc|adt|req|diagram)_[A-Za-z0-9_-]+\\b");

    private final IChatService chatService;
    private final String agentId;
    private final String modelVersion;
    private final String promptVersion;
    private final String schemaVersion;
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvalDraftSanitizer sanitizer = new EvalDraftSanitizer();

    public ChatSemanticAnomalyMiner(IChatService chatService,
                                    @Value("${zipp.evaluation.semantic-miner-agent-id:300015}") String agentId,
                                    @Value("${zipp.evaluation.semantic-miner-model-version:unconfigured}") String modelVersion,
                                    @Value("${zipp.evaluation.semantic-miner-prompt-version:semantic-miner-prompt-v1}") String promptVersion,
                                    @Value("${zipp.evaluation.semantic-miner-schema-version:semantic-miner-schema-v1}") String schemaVersion) {
        this.chatService = chatService;
        this.agentId = agentId;
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.schemaVersion = schemaVersion;
    }

    @Override
    public Finding analyze(String sanitizedTraceProjection) {
        if (StringUtils.isBlank(sanitizedTraceProjection)) throw new IllegalArgumentException("projection is required");
        String sessionId = chatService.createSession(agentId, "semantic-miner-system");
        List<String> replies = chatService.handleMessage(agentId, "semantic-miner-system", sessionId,
                prompt(sanitizedTraceProjection));
        if (replies == null || replies.isEmpty()) throw new IllegalStateException("semantic miner returned no output");
        return parse(replies.get(replies.size() - 1));
    }

    @Override
    public String version() {
        return "model=" + modelVersion + ";prompt=" + promptVersion + ";schema=" + schemaVersion;
    }

    private String prompt(String projection) {
        return "Analyze the sanitized_trace_projection for a potential semantic Agent anomaly. "
                + "Return JSON only with exactly: isPotentialAnomaly(boolean), confidence(number 0..1), "
                + "failureFamily(FALSE_SUCCESS|INTENT_MISMATCH|CLARIFICATION_FAILURE|TRAJECTORY_WASTE|NONE), "
                + "evidence(string array), suggestedRisk(critical|high|medium|low), requiresHumanReview(boolean). "
                + "Never include identifiers, identities, URLs, secrets, XML, approval, publication, or gate decisions.\n"
                + "sanitized_trace_projection:\n" + projection;
    }

    private Finding parse(String output) {
        try {
            if (StringUtils.isBlank(output) || PRODUCTION_ID.matcher(output).find()) {
                throw new IllegalArgumentException("unsafe semantic miner output");
            }
            EvalDraftSanitizer.Result outputSafety = sanitizer.sanitize(List.of(output));
            if (!outputSafety.safeForModel() || !outputSafety.removedCategories().isEmpty()) {
                throw new IllegalArgumentException("unsafe semantic miner output");
            }
            JsonNode root = mapper.readTree(output);
            validateKeys(root);
            boolean anomaly = requiredBoolean(root, "isPotentialAnomaly");
            boolean humanReview = requiredBoolean(root, "requiresHumanReview");
            if (!root.path("confidence").isNumber()) throw new IllegalArgumentException("confidence must be numeric");
            double confidence = root.path("confidence").asDouble(Double.NaN);
            String family = requiredText(root, "failureFamily");
            String risk = requiredText(root, "suggestedRisk");
            List<String> evidence = strings(root.path("evidence"));
            if (Double.isNaN(confidence) || confidence < 0D || confidence > 1D
                    || !FAMILIES.contains(family) || !RISKS.contains(risk)
                    || (anomaly && ("NONE".equals(family) || evidence.isEmpty() || !humanReview))
                    || (!anomaly && !"NONE".equals(family))) {
                throw new IllegalArgumentException("invalid semantic miner schema values");
            }
            return new Finding(anomaly, confidence, family, evidence, risk, humanReview);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid semantic miner output", e);
        }
    }

    private void validateKeys(JsonNode root) {
        if (!root.isObject()) throw new IllegalArgumentException("semantic miner output must be an object");
        root.fieldNames().forEachRemaining(field -> {
            if (!KEYS.contains(field)) throw new IllegalArgumentException("unexpected semantic miner field: " + field);
        });
        if (root.size() != KEYS.size()) throw new IllegalArgumentException("semantic miner output is incomplete");
    }

    private String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException("missing " + field);
        return value;
    }

    private boolean requiredBoolean(JsonNode root, String field) {
        if (!root.path(field).isBoolean()) throw new IllegalArgumentException("invalid " + field);
        return root.path(field).asBoolean();
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("evidence must be an array");
        if (StreamSupport.stream(node.spliterator(), false).anyMatch(value -> !value.isTextual())) {
            throw new IllegalArgumentException("evidence values must be strings");
        }
        return StreamSupport.stream(node.spliterator(), false)
                .filter(JsonNode::isTextual).map(JsonNode::asText).filter(StringUtils::isNotBlank).toList();
    }
}
