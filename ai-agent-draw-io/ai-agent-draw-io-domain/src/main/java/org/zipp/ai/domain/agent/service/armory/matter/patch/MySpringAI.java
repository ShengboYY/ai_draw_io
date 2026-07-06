package org.zipp.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.zipp.ai.domain.agent.service.chat.ProviderCatalog;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Spring AI 补丁
 */
public class MySpringAI extends BaseLlm {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;

    public MySpringAI(ChatModel chatModel) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel) {
        super(extractModelName(streamingChatModel));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(ChatModel chatModel, StreamingChatModel streamingChatModel, String modelName) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
    }

    public MySpringAI(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            ChatModel chatModel, String modelName, SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    public MySpringAI(
            StreamingChatModel streamingChatModel,
            String modelName,
            SpringAIProperties.Observability observabilityConfig) {
        super(Objects.requireNonNull(modelName, "model name cannot be null"));
        this.chatModel =
                (streamingChatModel instanceof ChatModel) ? (ChatModel) streamingChatModel : null;
        this.streamingChatModel =
                Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(
                        Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
        disableInternalToolExecution(prompt);
        observabilityHandler.logRequest(prompt.toString(), model());

        Throwable failure;
        try {
            return emitSuccess(context, chatModel.call(prompt));
        } catch (Exception e) {
            // B3 self-heal: if the endpoint rejected response_format (400), retry once without it and
            // demote only this request's credential/endpoint scope.
            Prompt retryPrompt = structuredOutputRetryPrompt(prompt, e);
            if (retryPrompt != null) {
                disableInternalToolExecution(retryPrompt);
                try {
                    return emitSuccess(context, chatModel.call(retryPrompt));
                } catch (Exception retryError) {
                    failure = retryError;
                }
            } else {
                failure = e;
            }
        }

        observabilityHandler.recordError(context, failure);
        SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(failure);
        return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), failure));
    }

    private Flowable<LlmResponse> emitSuccess(SpringAIObservabilityHandler.RequestContext context,
                                              ChatResponse chatResponse) {
        LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);
        observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());
        observabilityHandler.recordSuccess(context,
                extractTokenCount(chatResponse),
                extractInputTokenCount(chatResponse),
                extractOutputTokenCount(chatResponse));
        return Flowable.just(llmResponse);
    }

    /**
     * If {@code response_format} was set and the provider replied 400, return a copy of the prompt
     * with {@code response_format} stripped. When the prompt carries a scope, demote only that concrete
     * credential/endpoint so a bad custom endpoint does not poison every credential for the provider.
     * Returns {@code null} when there is nothing to heal, so the original error propagates unchanged.
     */
    private Prompt structuredOutputRetryPrompt(Prompt prompt, Throwable error) {
        if (prompt == null
                || !(prompt.getOptions() instanceof OpenAiChatOptions options)
                || options.getResponseFormat() == null
                || !isBadRequest(error)) {
            return null;
        }
        String provider = options.getHttpHeaders() == null ? null : options.getHttpHeaders().get("X-Provider");
        String scope = options.getHttpHeaders() == null ? null : options.getHttpHeaders().get("X-Structured-Output-Scope");
        org.slf4j.LoggerFactory.getLogger(MySpringAI.class).warn(
                "[structured-output] provider={} scope={} rejected response_format (400); retrying without it.",
                provider, scope);
        if (provider != null && scope != null) {
            ProviderCatalog.downgrade(provider, scope);
        }
        OpenAiChatOptions retryOptions = options.copy();
        retryOptions.setResponseFormat(null);
        return new Prompt(prompt.getInstructions(), retryOptions);
    }

    private boolean isBadRequest(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof org.springframework.web.reactive.function.client.WebClientResponseException web) {
                return web.getStatusCode().value() == 400;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * Tools are registered as ADK BaseTools (see AgentNode/SpringToolCallbackAdkTool), so ADK must own
     * tool execution and emit the function-call/response events that stream localized patches (e.g.
     * patch_cells) back to the canvas. Spring AI would otherwise run the tools inline
     * (internalToolExecutionEnabled defaults to true) and swallow those events, leaving the frontend
     * with "no valid response". Disable inline execution so the function call propagates to ADK.
     */
    // [diag] surface the provider's error body (e.g. OpenAI 400 detail), which WebClientResponseException
    // hides behind a generic "400 Bad Request" message.
    private void logHttpErrorBody(Throwable error) {
        try {
            Throwable cursor = error;
            while (cursor != null) {
                if (cursor instanceof org.springframework.web.reactive.function.client.WebClientResponseException web) {
                    org.slf4j.LoggerFactory.getLogger(MySpringAI.class).error(
                            "[diag-http] {} body={}", web.getStatusCode(), web.getResponseBodyAsString());
                    return;
                }
                cursor = cursor.getCause();
            }
        } catch (Exception ignore) {
            org.slf4j.LoggerFactory.getLogger(MySpringAI.class).warn("[diag-http] failed: {}", ignore.toString());
        }
    }

    private void disableInternalToolExecution(Prompt prompt) {
        if (prompt != null
                && prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null
                && !toolOptions.getToolCallbacks().isEmpty()) {
            toolOptions.setInternalToolExecutionEnabled(false);
        }
    }

    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "streaming");

        return Flowable.create(
                emitter -> {
                    try {
                        Prompt prompt = messageConverter.toLlmPrompt(llmRequest);
                        disableInternalToolExecution(prompt);
                        observabilityHandler.logRequest(prompt.toString(), model());

                        // [diag] confirm whether tools survive into the request
                        try {
                            org.springframework.ai.chat.prompt.ChatOptions po = prompt.getOptions();
                            int promptTools = (po instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tcc && tcc.getToolCallbacks() != null) ? tcc.getToolCallbacks().size() : -1;
                            int defaultTools = -1;
                            if (streamingChatModel instanceof org.springframework.ai.openai.OpenAiChatModel ocm
                                    && ocm.getDefaultOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions dtc
                                    && dtc.getToolCallbacks() != null) {
                                defaultTools = dtc.getToolCallbacks().size();
                            }
                            org.slf4j.LoggerFactory.getLogger(MySpringAI.class).info("[diag-tools] promptOptions={} promptToolCallbacks={} defaultToolCallbacks={}", po == null ? "null" : po.getClass().getSimpleName(), promptTools, defaultTools);
                        } catch (Exception ignore) {
                            org.slf4j.LoggerFactory.getLogger(MySpringAI.class).warn("[diag-tools] failed: {}", ignore.toString());
                        }

                        Flux<ChatResponse> responseFlux = streamingChatModel.stream(prompt);

                        responseFlux
                                .doOnError(
                                        error -> {
                                            logHttpErrorBody(error);
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        })
                                .subscribe(
                                        chatResponse -> {
                                            try {
                                                // Use enhanced streaming-aware conversion
                                                LlmResponse llmResponse =
                                                        messageConverter.toLlmResponse(chatResponse, true);
                                                emitter.onNext(llmResponse);
                                            } catch (Exception e) {
                                                observabilityHandler.recordError(context, e);
                                                SpringAIErrorMapper.MappedError mappedError =
                                                        SpringAIErrorMapper.mapError(e);
                                                emitter.onError(
                                                        new RuntimeException(mappedError.getNormalizedMessage(), e));
                                            }
                                        },
                                        error -> {
                                            observabilityHandler.recordError(context, error);
                                            SpringAIErrorMapper.MappedError mappedError =
                                                    SpringAIErrorMapper.mapError(error);
                                            emitter.onError(
                                                    new RuntimeException(mappedError.getNormalizedMessage(), error));
                                        },
                                        () -> {
                                            // Record success for streaming completion
                                            observabilityHandler.recordSuccess(context, 0, 0, 0);
                                            emitter.onComplete();
                                        });
                    } catch (Exception e) {
                        observabilityHandler.recordError(context, e);
                        SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);
                        emitter.onError(new RuntimeException(mappedError.getNormalizedMessage(), e));
                    }
                },
                BackpressureStrategy.BUFFER);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }
    
}
