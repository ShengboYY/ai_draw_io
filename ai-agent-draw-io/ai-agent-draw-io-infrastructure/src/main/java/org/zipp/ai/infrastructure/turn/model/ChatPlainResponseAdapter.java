package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainResponseGenerationPort;
import org.zipp.ai.application.turn.PlainResponseGenerationRequest;
import org.zipp.ai.application.turn.PlainResponseGenerationResult;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.util.Set;
import java.util.concurrent.CancellationException;

/** Tool-free source-free response adapter used by the V2 response path. */
@Component
public final class ChatPlainResponseAdapter implements PlainResponseGenerationPort {

    private static final Set<String> FIELDS = Set.of("assistantMessage", "payloadRef");
    private static final int MAX_ASSISTANT_MESSAGE_LENGTH = 16_000;
    private static final int MAX_PAYLOAD_REF_LENGTH = 255;

    private final ToolFreeChatModelInvoker model;
    private final PlainGenerationPromptRenderer renderer = new PlainGenerationPromptRenderer();

    // Select the production constructor; the package-private overload is test-only.
    @Autowired
    public ChatPlainResponseAdapter(
            IChatService chat,
            @Value("${zipp.turn.v2.plain-agent-id:300025}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "v2-plain-response"));
    }

    ChatPlainResponseAdapter(ToolFreeChatModelInvoker model) {
        this.model = model;
    }

    @Override
    public PlainResponseGenerationResult generate(
            PlainResponseGenerationRequest request,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        if (request == null || events == null) {
            throw new IllegalArgumentException("plain response request and events are required");
        }
        final String output;
        try {
            output = model.invoke(request.modelInputBinding(), renderer.render(request), cancellation);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("V2_PLAIN_RESPONSE_MODEL_UNAVAILABLE", exception);
        }
        try {
            return parse(output, request);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("V2_PLAIN_RESPONSE_MODEL_OUTPUT_INVALID", exception);
        }
    }

    private PlainResponseGenerationResult parse(
            String output,
            PlainResponseGenerationRequest request
    ) {
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("plain response output fields are not exact");
        }
        String payloadRef = root.getString("payloadRef");
        if (payloadRef == null || payloadRef.isBlank()) {
            // A response identifier is server metadata; a harmless model omission must not fail
            // an otherwise valid clarification or answer.
            payloadRef = "response-" + request.attempt().key().turnId();
        } else if (payloadRef.length() > MAX_PAYLOAD_REF_LENGTH) {
            throw new IllegalArgumentException("payloadRef is invalid");
        }
        return new PlainResponseGenerationResult(
                bounded(root, "assistantMessage", MAX_ASSISTANT_MESSAGE_LENGTH),
                payloadRef);
    }

    private String bounded(JSONObject root, String field, int limit) {
        String value = root.getString(field);
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
