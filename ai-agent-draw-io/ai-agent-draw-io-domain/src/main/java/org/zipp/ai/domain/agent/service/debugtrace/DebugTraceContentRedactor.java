package org.zipp.ai.domain.agent.service.debugtrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes credentials and hidden reasoning content before a debug payload reaches persistence. */
final class DebugTraceContentRedactor {

    static final String REDACTED = "[REDACTED]";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SECRET_KEYS = Set.of(
            "authorization", "apikey", "accesstoken", "refreshtoken", "clientsecret",
            "password", "credential", "credentials", "secret", "token");
    private static final Set<String> HIDDEN_REASONING_KEYS = Set.of(
            "reasoning", "thinking", "chainofthought", "thoughtcontent", "thoughtsignature");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;\\\"]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|credential|secret)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern STANDALONE_SECRET = Pattern.compile(
            "(?i)\\b(?:sk|rk|pk|api)[-_][A-Za-z0-9_-]{8,}\\b|\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern THINKING_BLOCK = Pattern.compile(
            "(?is)<(?:thinking|reasoning|chain_of_thought)>.*?</(?:thinking|reasoning|chain_of_thought)>");

    private DebugTraceContentRedactor() {
    }

    static String redact(String content, String contentType) {
        if (StringUtils.isBlank(content)) {
            return content;
        }
        if (isJson(contentType, content)) {
            try {
                JsonNode root = MAPPER.readTree(content);
                root = redactNode(root);
                return MAPPER.writeValueAsString(root);
            } catch (Exception ignored) {
                // Invalid JSON still receives the text-pattern safety pass below.
            }
        }
        return redactText(content);
    }

    private static String redactText(String content) {
        String redacted = THINKING_BLOCK.matcher(content).replaceAll(REDACTED);
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
        return STANDALONE_SECRET.matcher(redacted).replaceAll(REDACTED);
    }

    private static boolean isJson(String contentType, String content) {
        String trimmed = content.trim();
        return StringUtils.containsIgnoreCase(contentType, "json")
                || trimmed.startsWith("{")
                || trimmed.startsWith("[");
    }

    private static JsonNode redactNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(redactText(node.textValue()));
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, redactNode(array.get(index)));
            }
            return array;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode object = (ObjectNode) node;
        boolean thoughtPart = object.path("thought").asBoolean(false);
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String normalized = normalizeKey(field.getKey());
            JsonNode value = field.getValue();
            if (SECRET_KEYS.contains(normalized)
                    || HIDDEN_REASONING_KEYS.contains(normalized)
                    || (thoughtPart && "text".equals(normalized))) {
                object.put(field.getKey(), REDACTED);
            } else {
                object.set(field.getKey(), redactNode(value));
            }
        }
        return object;
    }

    private static String normalizeKey(String key) {
        return StringUtils.defaultString(key).replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }
}
