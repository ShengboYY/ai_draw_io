package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.EvidenceAnswerGenerationPort;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.infrastructure.adapter.repository.MySqlEvidencePreparationStore;

import java.util.Set;

/** Evidence-only answer adapter; the application request structurally disables AI knowledge. */
@Component
public final class ChatEvidenceAnswerGenerationAdapter implements EvidenceAnswerGenerationPort {

    private static final Set<String> FIELDS = Set.of("assistantMessage", "payloadRef", "claimManifestRef");
    private final ToolFreeChatModelInvoker model;
    private final MySqlEvidencePreparationStore preparations;
    private final SourceEvidencePromptRenderer renderer = new SourceEvidencePromptRenderer();

    @Autowired
    public ChatEvidenceAnswerGenerationAdapter(
            IChatService chat,
            MySqlEvidencePreparationStore preparations,
            @Value("${zipp.turn.v2.evidence-agent-id:300028}") String agentId
    ) {
        this.model = new ToolFreeChatModelInvoker(chat, agentId, "v2-evidence-answer");
        this.preparations = preparations;
    }

    @Override
    public Result generate(Request request, CancellationSignal cancellation) {
        if (request.aiKnowledgeAllowed()) {
            throw new IllegalStateException("EVIDENCE_ANSWER_AI_KNOWLEDGE_MUST_BE_DISABLED");
        }
        MySqlEvidencePreparationStore.Prepared prepared = preparations.find(
                        request.attempt(), request.preparedEvidenceRef())
                .orElseThrow(() -> new IllegalStateException("EVIDENCE_PREPARATION_NOT_FOUND"));
        if (!prepared.planFingerprint().equals(request.planIdentity().planFingerprint())) {
            throw new IllegalStateException("EVIDENCE_PREPARATION_BINDING_MISMATCH");
        }
        String rendered = renderer.render(request.context(), prepared.preparedRef(),
                prepared.manifestDigest(), prepared.items(), false,
                request.includeCanvasContext());
        String output = model.invoke(ModelInputBinding.bound(
                request.attempt().key(), request.readSet().digest(),
                request.attempt().inputBindingDigest()), rendered, cancellation);
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalStateException("V2_EVIDENCE_MODEL_OUTPUT_INVALID");
        }
        return new Result(text(root, "payloadRef", 255),
                text(root, "assistantMessage", 16_000),
                text(root, "claimManifestRef", 64));
    }

    private String text(JSONObject root, String field, int maximum) {
        String value = root.getString(field);
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalStateException(field + " is invalid");
        }
        return value.trim();
    }
}
