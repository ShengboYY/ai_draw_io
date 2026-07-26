package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.GroundedGenerationPort;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.infrastructure.adapter.repository.MySqlEvidencePreparationStore;

import java.util.Set;

/** Tool-free grounded drawing adapter backed by durable prepared evidence. */
@Component
@ConditionalOnProperty(name = "zipp.turn.v2.grounded-generation.enabled", havingValue = "true")
public final class ChatGroundedGenerationAdapter implements GroundedGenerationPort {

    private static final Set<String> FIELDS = Set.of(
            "canvasXml", "assistantMessage", "payloadRef", "citationManifestRef");
    private final ToolFreeChatModelInvoker model;
    private final MySqlEvidencePreparationStore preparations;
    private final SourceEvidencePromptRenderer renderer = new SourceEvidencePromptRenderer();

    @Autowired
    public ChatGroundedGenerationAdapter(
            IChatService chat,
            MySqlEvidencePreparationStore preparations,
            @Value("${zipp.turn.v2.grounded-agent-id:300027}") String agentId
    ) {
        this.model = new ToolFreeChatModelInvoker(chat, agentId, "v2-grounded-generation");
        this.preparations = preparations;
    }

    @Override
    public Result generate(Request request) {
        MySqlEvidencePreparationStore.Prepared prepared = preparations.find(
                        request.attempt(), request.preparedEvidenceRef())
                .orElseThrow(() -> new IllegalStateException("GROUNDED_PREPARATION_NOT_FOUND"));
        if (!prepared.planFingerprint().equals(request.plan().identity().planFingerprint())) {
            throw new IllegalStateException("GROUNDED_PREPARATION_BINDING_MISMATCH");
        }
        String rendered = renderer.render(request.context(), prepared.preparedRef(),
                prepared.manifestDigest(), prepared.items(), true);
        String output = model.invoke(ModelInputBinding.bound(
                request.attempt().key(), request.readSet().digest(),
                request.attempt().inputBindingDigest()), rendered);
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalStateException("V2_GROUNDED_MODEL_OUTPUT_INVALID");
        }
        String canvasXml = text(root, "canvasXml", 4_000_000);
        if (!canvasXml.startsWith("<mxGraphModel") || !canvasXml.endsWith("</mxGraphModel>")
                || canvasXml.contains("<!DOCTYPE") || canvasXml.contains("<!ENTITY")) {
            throw new IllegalStateException("V2_GROUNDED_CANVAS_INVALID");
        }
        return new Result(text(root, "payloadRef", 255), canvasXml,
                text(root, "assistantMessage", 16_000),
                text(root, "citationManifestRef", 64));
    }

    private String text(JSONObject root, String field, int maximum) {
        String value = root.getString(field);
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalStateException(field + " is invalid");
        }
        return value.trim();
    }
}
