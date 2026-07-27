package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.PlainGenerationResult;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.util.Set;
import java.util.concurrent.CancellationException;

/** Tool-free Plain model adapter; disabled by default until an isolated V2 executor is enabled. */
@Component
public final class ChatPlainGenerationAdapter implements PlainGenerationPort {

    private static final Set<String> FIELDS = Set.of("canvasXml", "assistantMessage", "payloadRef");
    private static final int MAX_CANVAS_XML_LENGTH = 4_000_000;
    private static final int MAX_ASSISTANT_MESSAGE_LENGTH = 16_000;
    private final ToolFreeChatModelInvoker model;
    private final PlainGenerationPromptRenderer renderer = new PlainGenerationPromptRenderer();

    // Select the production constructor; the package-private overload is test-only.
    @Autowired
    public ChatPlainGenerationAdapter(
            IChatService chat,
            @Value("${zipp.turn.v2.plain-agent-id:300025}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "v2-plain-generation"));
    }

    ChatPlainGenerationAdapter(ToolFreeChatModelInvoker model) {
        this.model = model;
    }

    @Override
    public PlainGenerationResult generate(
            PlainGenerationRequest request,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        if (request == null || events == null) {
            throw new IllegalArgumentException("plain generation request and events are required");
        }
        final String output;
        try {
            output = model.invoke(request.modelInputBinding(), renderer.render(request), cancellation);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("V2_PLAIN_MODEL_UNAVAILABLE", exception);
        }
        try {
            return parse(output);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("V2_PLAIN_MODEL_OUTPUT_INVALID", exception);
        }
    }

    private PlainGenerationResult parse(String output) {
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("plain output fields are not exact");
        }
        String canvasXml = bounded(root, "canvasXml", MAX_CANVAS_XML_LENGTH);
        if (!canvasXml.startsWith("<mxGraphModel") || !canvasXml.endsWith("</mxGraphModel>")
                || canvasXml.contains("<!DOCTYPE") || canvasXml.contains("<!ENTITY")) {
            throw new IllegalArgumentException("plain canvas XML is not a safe mxGraphModel");
        }
        return new PlainGenerationResult(
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
