package org.zipp.ai.domain.agent.service.evaluation.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

/** Visual discovery adapter with a closed output schema and inline image transport. */
@Service
public class ChatVisualAnomalyMiner implements IVisualAnomalyMiner {
    private static final Set<String> FIELDS = Set.of("potentialIssue", "confidence", "issueFamily", "evidence",
            "suggestedRisk", "syntheticReconstructionSuggestion", "requiresHumanReview");
    private static final Set<String> FAMILIES = Set.of("READABILITY", "LAYOUT_HIERARCHY", "VISUAL_CLUTTER", "STYLE_COHERENCE", "NONE");
    private static final Set<String> RISKS = Set.of("critical", "high", "medium", "low");
    private static final Set<String> EVIDENCE_CODES = Set.of("TEXT_TOO_SMALL", "LOW_CONTRAST", "WEAK_HIERARCHY",
            "EXCESSIVE_DENSITY", "INCONSISTENT_GROUPING", "EDGE_TRACE_DIFFICULT", "STYLE_INCOHERENT");
    private static final Set<String> RECONSTRUCTION_CODES = Set.of("SYNTHETIC_SMALL_LABEL_FLOW", "SYNTHETIC_LOW_CONTRAST_FLOW",
            "SYNTHETIC_FLAT_HIERARCHY", "SYNTHETIC_DENSE_GRAPH", "SYNTHETIC_INCONSISTENT_GROUPS",
            "SYNTHETIC_TANGLED_EDGES", "SYNTHETIC_INCOHERENT_STYLES", "NONE");
    private static final String PROMPT = "visual-miner-prompt-v1";
    private static final String SCHEMA = "visual-miner-schema-v1";
    private final IChatService chat;
    private final String agentId;
    private final String modelVersion;
    private final double temperature;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatVisualAnomalyMiner(IChatService chat,
            @Value("${zipp.evaluation.visual-miner-agent-id:300017}") String agentId,
            @Value("${zipp.evaluation.visual-miner-model-version:unconfigured}") String modelVersion,
            @Value("${zipp.evaluation.visual-miner-temperature:0}") double temperature) {
        this.chat = chat; this.agentId = agentId; this.modelVersion = modelVersion; this.temperature = temperature;
    }

    @Override public Finding analyze(Input input) {
        if (input == null || input.image() == null || input.image().bytes().length == 0) throw new IllegalArgumentException("visual pixels are required");
        String session = chat.createSession(agentId, "visual-miner-system");
        String prompt = "Inspect this rendered diagram for subjective quality issues not reliably established by geometry rules. "
                + "Return JSON only with exactly potentialIssue(boolean), confidence(number 0..1), issueFamily(READABILITY|LAYOUT_HIERARCHY|VISUAL_CLUTTER|STYLE_COHERENCE|NONE), "
                + "evidence(array of TEXT_TOO_SMALL|LOW_CONTRAST|WEAK_HIERARCHY|EXCESSIVE_DENSITY|INCONSISTENT_GROUPING|EDGE_TRACE_DIFFICULT|STYLE_INCOHERENT), "
                + "suggestedRisk(critical|high|medium|low), syntheticReconstructionSuggestion(one of SYNTHETIC_SMALL_LABEL_FLOW|SYNTHETIC_LOW_CONTRAST_FLOW|SYNTHETIC_FLAT_HIERARCHY|SYNTHETIC_DENSE_GRAPH|SYNTHETIC_INCONSISTENT_GROUPS|SYNTHETIC_TANGLED_EDGES|SYNTHETIC_INCOHERENT_STYLES|NONE), requiresHumanReview(boolean). "
                + "Never include names, identifiers, exact private labels, XML, URLs, workflow approval or gate decisions. diagramType=" + StringUtils.left(StringUtils.defaultString(input.diagramType()), 80)
                + "; analyzerEvidence=" + input.analyzerEvidence() + "; prompt=" + PROMPT + "; schema=" + SCHEMA;
        ChatCommandEntity command = ChatCommandEntity.builder().agentId(agentId).userId("visual-miner-system").sessionId(session)
                .texts(List.of(new ChatCommandEntity.Content.Text(prompt))).files(List.of())
                .inlineDatas(List.of(new ChatCommandEntity.Content.InlineData(input.image().bytes(), input.image().mimeType()))).build();
        List<String> replies = chat.handleMessage(command);
        if (replies == null || replies.isEmpty()) throw new IllegalStateException("visual miner returned no output");
        return parse(replies.get(replies.size() - 1));
    }

    @Override public String version() { return "model=" + modelVersion + ";temperature=" + temperature + ";prompt=" + PROMPT + ";schema=" + SCHEMA; }

    private Finding parse(String output) {
        try {
            JsonNode root = mapper.readTree(output); Set<String> actual = new HashSet<>(); root.fieldNames().forEachRemaining(actual::add);
            if (!root.isObject() || !FIELDS.equals(actual)) throw new IllegalArgumentException("invalid visual miner fields");
            if (!root.path("potentialIssue").isBoolean() || !root.path("requiresHumanReview").isBoolean() || !root.path("confidence").isNumber()) throw new IllegalArgumentException("invalid visual miner values");
            boolean issue = root.path("potentialIssue").asBoolean(); double confidence = root.path("confidence").asDouble();
            String family = root.path("issueFamily").asText(); String risk = root.path("suggestedRisk").asText();
            String suggestion = StringUtils.trimToEmpty(root.path("syntheticReconstructionSuggestion").asText());
            List<String> evidence = strings(root.path("evidence")); boolean review = root.path("requiresHumanReview").asBoolean();
            if (confidence < 0D || confidence > 1D || !FAMILIES.contains(family) || !RISKS.contains(risk)
                    || evidence.stream().anyMatch(value -> !EVIDENCE_CODES.contains(value)) || !RECONSTRUCTION_CODES.contains(suggestion)
                    || (issue && ("NONE".equals(family) || evidence.isEmpty() || "NONE".equals(suggestion) || !review))
                    || (!issue && !"NONE".equals(family))) throw new IllegalArgumentException("invalid visual miner schema values");
            return new Finding(issue, confidence, family, evidence, risk, StringUtils.left(suggestion, 500), review);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("invalid visual miner output", e); }
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray() || node.size() > 8) throw new IllegalArgumentException("invalid evidence");
        return StreamSupport.stream(node.spliterator(), false).filter(JsonNode::isTextual).map(JsonNode::asText)
                .filter(StringUtils::isNotBlank).map(value -> StringUtils.left(value, 300)).toList();
    }
}
