package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.SemanticIntentOutcome;
import org.zipp.ai.application.turn.classification.SemanticIntentReady;
import org.zipp.ai.application.turn.classification.SemanticIntentRouterPort;
import org.zipp.ai.application.turn.classification.SemanticIntentUnavailable;
import org.zipp.ai.application.turn.classification.SemanticRouterInput;
import org.zipp.ai.application.turn.classification.SemanticRouterPromptRenderer;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.Set;

/** Concrete V2 Semantic Router adapter with a strict, tool-free model boundary. */
@Component
public final class ChatSemanticIntentRouterAdapter implements SemanticIntentRouterPort {

    private static final Set<String> FIELDS = Set.of(
            "action", "outputIntent", "targetNeed", "diagramType", "skillName");
    private final ToolFreeChatModelInvoker model;
    private final SemanticRouterPromptRenderer renderer = new SemanticRouterPromptRenderer();

    public ChatSemanticIntentRouterAdapter(
            IChatService chat,
            @Value("${zipp.turn.v2.semantic-router-agent-id:300023}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "v2-semantic-router"));
    }

    ChatSemanticIntentRouterAdapter(ToolFreeChatModelInvoker model) {
        this.model = model;
    }

    @Override
    public SemanticIntentOutcome route(SemanticRouterInput input) {
        if (input == null) {
            return new SemanticIntentUnavailable("V2_SEMANTIC_ROUTER_INPUT_INVALID");
        }
        final String output;
        try {
            if (!input.modelInputBinding().isBound()
                    || !input.modelInputBinding().inputDigest().equals(input.inputDigest())) {
                return new SemanticIntentUnavailable("V2_SEMANTIC_ROUTER_MODEL_INPUT_INVALID");
            }
            output = model.invoke(input.modelInputBinding(), renderer.render(input));
        } catch (RuntimeException exception) {
            return new SemanticIntentUnavailable("V2_SEMANTIC_ROUTER_MODEL_UNAVAILABLE");
        }
        try {
            return new SemanticIntentReady(parse(output));
        } catch (RuntimeException exception) {
            return new SemanticIntentUnavailable("V2_SEMANTIC_ROUTER_OUTPUT_INVALID");
        }
    }

    private SemanticIntent parse(String output) {
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("semantic router output fields are not exact");
        }
        return new SemanticIntent(
                SemanticAction.valueOf(required(root, "action", 64)),
                OutputIntent.valueOf(required(root, "outputIntent", 64)),
                targetNeed(required(root, "targetNeed", 64)),
                bounded(root, "diagramType", 128),
                bounded(root, "skillName", 256));
    }

    private TargetNeed targetNeed(String value) {
        return switch (value) {
            case "NOT_REQUIRED" -> TargetNeed.NOT_REQUIRED;
            case "CANVAS_REQUIRED" -> TargetNeed.CANVAS_REQUIRED;
            case "CANVAS_OPTIONAL" -> TargetNeed.CANVAS_OPTIONAL;
            case "CLARIFICATION_REQUIRED" -> TargetNeed.CLARIFICATION_REQUIRED;
            default -> throw new IllegalArgumentException("unknown target need");
        };
    }

    private String required(JSONObject root, String field, int limit) {
        return bounded(root, field, limit);
    }

    private String bounded(JSONObject root, String field, int limit) {
        String value = root.getString(field);
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
