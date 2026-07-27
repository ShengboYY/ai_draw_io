package org.zipp.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.application.turn.MatchedInstructionSpan;
import org.zipp.ai.application.turn.MemoryWriteDeclaration;
import org.zipp.ai.application.turn.MemoryWriteRuleVersion;
import org.zipp.ai.application.turn.MemoryWriteSemanticDigest;
import org.zipp.ai.application.turn.NoMemoryWrite;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;

/** Keeps the durable assignment payload typed at the application boundary. */
final class MemoryWriteDeclarationJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    String encode(MemoryWriteDeclaration declaration) {
        try {
            if (declaration instanceof NoMemoryWrite) {
                return mapper.createObjectNode().put("kind", "NONE").toString();
            }
            RememberDecisionDeclaration remember = (RememberDecisionDeclaration) declaration;
            return mapper.createObjectNode()
                    .put("kind", "REMEMBER_DECISION")
                    .put("schemaVersion", remember.schemaVersion())
                    .put("ruleVersion", remember.ruleVersion().value())
                    .put("matchedSpan", remember.matchedSpan().value())
                    .put("digest", remember.digest().value())
                    .put("chartbookId", remember.chartbookId())
                    .put("decisionKey", remember.decisionKey())
                    .put("applicabilityStage", remember.applicabilityStage())
                    .put("canonicalText", remember.canonicalText())
                    .put("locale", remember.locale())
                    .toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("memory write declaration cannot be encoded", e);
        }
    }

    MemoryWriteDeclaration decode(String json) {
        if (json == null || json.isBlank()) {
            return new NoMemoryWrite();
        }
        try {
            JsonNode node = mapper.readTree(json);
            if ("NONE".equals(node.path("kind").asText())) {
                return new NoMemoryWrite();
            }
            return new RememberDecisionDeclaration(
                    node.path("schemaVersion").asInt(),
                    new MemoryWriteRuleVersion(node.path("ruleVersion").asText()),
                    new MatchedInstructionSpan(node.path("matchedSpan").asText()),
                    new MemoryWriteSemanticDigest(node.path("digest").asText()),
                    node.path("chartbookId").asText(),
                    optionalText(node, "decisionKey"),
                    optionalText(node, "applicabilityStage"),
                    optionalText(node, "canonicalText"),
                    optionalText(node, "locale")
            );
        } catch (Exception e) {
            throw new IllegalStateException("persisted memory write declaration is unavailable", e);
        }
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
