package org.zipp.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.zipp.ai.application.turn.ClarificationId;
import org.zipp.ai.application.turn.ClarificationReplyDeclaration;
import org.zipp.ai.application.turn.NoClarificationReply;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.ReplyToClarification;
import org.zipp.ai.application.turn.RequestedDiagramSkill;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.UntrustedLegacyVersionDeclaration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encodes only bounded declarations needed to rebuild a pinned takeover input. */
final class TurnInputBindingJsonCodec {
    static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 16 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryWriteDeclarationJsonCodec memoryCodec = new MemoryWriteDeclarationJsonCodec();

    String encode(TurnDeclarations declarations) {
        if (declarations == null) {
            throw new IllegalArgumentException("declarations must not be null");
        }
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", SCHEMA_VERSION);
        ArrayNode attachments = root.putArray("currentTurnAttachments");
        declarations.currentTurnAttachments().forEach(ref -> attachments.add(ref.value()));

        ObjectNode clarification = root.putObject("clarificationReply");
        if (declarations.clarificationReply() instanceof NoClarificationReply) {
            clarification.put("kind", "NONE");
        } else {
            clarification.put("kind", "REPLY");
            clarification.put("id", ((ReplyToClarification) declarations.clarificationReply())
                    .clarificationId().value());
        }

        ArrayNode legacy = root.putArray("legacySelectedSources");
        declarations.legacySelectedSources().forEach(source -> legacy.add(source.value()));
        ArrayNode skills = root.putArray("requestedDiagramSkills");
        declarations.requestedDiagramSkills().forEach(skill -> skills.add(skill.value()));
        try {
            root.set("memoryWrite", mapper.readTree(memoryCodec.encode(declarations.memoryWrite())));
            String json = root.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                throw new IllegalArgumentException("turn input binding payload exceeds bound");
            }
            return json;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("turn input binding cannot be encoded", exception);
        }
    }

    TurnDeclarations decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("turn input binding payload is unavailable");
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                throw new IllegalStateException("unsupported turn input binding schema");
            }
            List<OpaqueConversationFileRef> attachments = new ArrayList<>();
            for (JsonNode ref : requiredArray(root, "currentTurnAttachments")) {
                attachments.add(new OpaqueConversationFileRef(requiredText(ref, "attachment")));
            }

            JsonNode clarification = root.path("clarificationReply");
            String clarificationKind = requiredText(clarification.path("kind"), "clarification kind");
            ClarificationReplyDeclaration reply = switch (clarificationKind) {
                case "NONE" -> new NoClarificationReply();
                case "REPLY" -> new ReplyToClarification(new ClarificationId(
                        requiredText(clarification.path("id"), "clarification id")));
                default -> throw new IllegalStateException("unknown clarification kind");
            };

            List<UntrustedLegacyVersionDeclaration> legacy = new ArrayList<>();
            for (JsonNode source : requiredArray(root, "legacySelectedSources")) {
                legacy.add(new UntrustedLegacyVersionDeclaration(requiredText(source, "legacy source")));
            }
            List<RequestedDiagramSkill> skills = new ArrayList<>();
            JsonNode requestedSkills = root.path("requestedDiagramSkills");
            // Schema-v1 rows written before V2 skill support decode as an empty declaration.
            if (!requestedSkills.isMissingNode()) {
                if (!requestedSkills.isArray()) {
                    throw new IllegalStateException("requestedDiagramSkills must be an array");
                }
                for (JsonNode skill : requestedSkills) {
                    skills.add(new RequestedDiagramSkill(requiredText(skill, "requested skill")));
                }
            }
            JsonNode memory = root.path("memoryWrite");
            if (memory.isMissingNode() || memory.isNull()) {
                throw new IllegalStateException("memory declaration is unavailable");
            }
            return new TurnDeclarations(
                    attachments,
                    reply,
                    legacy,
                    memoryCodec.decode(memory.toString()),
                    skills);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("turn input binding payload is unavailable", exception);
        }
    }

    private static ArrayNode requiredArray(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            throw new IllegalStateException(field + " must be an array");
        }
        return (ArrayNode) value;
    }

    private static String requiredText(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(field + " must be non-blank text");
        }
        return value.asText();
    }
}
