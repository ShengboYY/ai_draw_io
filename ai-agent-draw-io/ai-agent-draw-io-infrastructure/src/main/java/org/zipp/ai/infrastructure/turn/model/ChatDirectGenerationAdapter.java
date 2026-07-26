package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.DirectGenerationPort;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectPreparationStore;

import java.util.Set;

/** Tool-free Direct generation over a durable server-prepared visual projection. */
@Component
@ConditionalOnProperty(name = "zipp.turn.v2.direct-generation.enabled", havingValue = "true")
public final class ChatDirectGenerationAdapter implements DirectGenerationPort {

    private static final Set<String> FIELDS = Set.of("canvasXml", "assistantMessage", "payloadRef");
    private static final int MAX_CANVAS_XML_LENGTH = 4_000_000;
    private static final int MAX_ASSISTANT_MESSAGE_LENGTH = 16_000;

    private final ToolFreeChatModelInvoker model;
    private final MySqlDirectPreparationStore preparations;
    private final DirectGenerationPromptRenderer renderer = new DirectGenerationPromptRenderer();

    @Autowired
    public ChatDirectGenerationAdapter(
            IChatService chat,
            MySqlDirectPreparationStore preparations,
            @Value("${zipp.turn.v2.direct-agent-id:300026}") String agentId
    ) {
        this.model = new ToolFreeChatModelInvoker(chat, agentId, "v2-direct-generation");
        this.preparations = preparations;
    }

    @Override
    public Result generate(Request request) {
        if (request == null) throw new IllegalArgumentException("direct generation request is required");
        MySqlDirectPreparationStore.Prepared prepared = preparations.find(
                        request.attempt(), request.observation().observationRef())
                .orElseThrow(() -> new IllegalStateException("DIRECT_PREPARATION_NOT_FOUND"));
        if (!prepared.planFingerprint().equals(request.plan().identity().planFingerprint())
                || !prepared.observationFingerprint().equals(request.observation().observationFingerprint())) {
            throw new IllegalStateException("DIRECT_PREPARATION_BINDING_MISMATCH");
        }
        String rendered = renderer.render(request, prepared.canvasXml());
        String output;
        try {
            output = model.invoke(ModelInputBinding.bound(
                    request.attempt().key(), request.readSet().digest(),
                    request.attempt().inputBindingDigest()), rendered);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("V2_DIRECT_MODEL_UNAVAILABLE", failure);
        }
        try {
            return parse(output);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("V2_DIRECT_MODEL_OUTPUT_INVALID", failure);
        }
    }

    private Result parse(String output) {
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("direct output fields are not exact");
        }
        String canvasXml = bounded(root, "canvasXml", MAX_CANVAS_XML_LENGTH);
        if (!canvasXml.startsWith("<mxGraphModel") || !canvasXml.endsWith("</mxGraphModel>")
                || canvasXml.contains("<!DOCTYPE") || canvasXml.contains("<!ENTITY")) {
            throw new IllegalArgumentException("direct canvas XML is not a safe mxGraphModel");
        }
        return new Result(
                bounded(root, "payloadRef", 255),
                canvasXml,
                bounded(root, "assistantMessage", MAX_ASSISTANT_MESSAGE_LENGTH));
    }

    private String bounded(JSONObject root, String field, int limit) {
        String value = root.getString(field);
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
