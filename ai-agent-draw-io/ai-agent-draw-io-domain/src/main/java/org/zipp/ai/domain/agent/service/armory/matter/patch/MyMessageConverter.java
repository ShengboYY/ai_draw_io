package org.zipp.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.zipp.ai.domain.agent.service.chat.StructuredOutputSchemas;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 补丁
 */
public class MyMessageConverter extends MessageConverter {

    private final ObjectMapper objectMapper;

    public MyMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest) {

        List<Media> mediaList = new ArrayList<>();
        for (Content content : llmRequest.contents()) {
            for (Part part : content.parts().orElse(List.of())) {
                if (part.inlineData().isPresent()) {
                    // Handle inline media data (images, audio, video, etc.)
                    com.google.genai.types.Blob blob = part.inlineData().get();
                    if (blob.mimeType().isPresent() && blob.data().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
                            // Create Media object from inline data using ByteArrayResource
                            org.springframework.core.io.ByteArrayResource resource =
                                    new org.springframework.core.io.ByteArrayResource(blob.data().get());
                            mediaList.add(new Media(mimeType, resource));
                        } catch (Exception e) {
                            // Log warning but continue processing other parts
                            // In production, consider proper logging framework
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + blob.mimeType().get());
                        }
                    }
                } else if (part.fileData().isPresent()) {
                    // Handle file-based media (URI references)
                    com.google.genai.types.FileData fileData = part.fileData().get();
                    if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
                            // Create Media object from file URI
                            URI uri = URI.create(fileData.fileUri().get());
                            mediaList.add(new Media(mimeType, uri));
                        } catch (Exception e) {
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + fileData.mimeType().get());
                        }
                    }
                }
            }
        }

        Prompt llmPrompt = super.toLlmPrompt(llmRequest);
        llmPrompt.getUserMessage().getMedia().addAll(mediaList);

        // ADK 0.5.0's MessageConverter drops functionResponse parts (it renders them as an empty
        // UserMessage). When ADK owns tool execution, that leaves an assistant tool_calls message with
        // no matching tool result, which OpenAI rejects with 400. Replace those with proper
        // ToolResponseMessages so the tool_call_id is paired correctly.
        List<Message> repaired = repairToolResponseMessages(llmRequest, llmPrompt.getInstructions());
        if (repaired != null) {
            llmPrompt = new Prompt(repaired, llmPrompt.getOptions());
        }

        // CustomConfigPlugin 通过 llmRequest 的 httpOptions headers 传递三类信号：
        //  - X-Custom-*            自定义 base-url / api-key / completions-path
        //  - X-Custom-Model-Selected  用户主动选择的自定义模型
        //  - X-Structured-Output   结构化输出档位(json_object|json_schema)，仅无 tool 的 agent 生效
        Map<String, String> reqHeaders = extractRequestHeaders(llmRequest);
        boolean hasCustomHeaders = reqHeaders.containsKey("X-Custom-Base-Url")
                || reqHeaders.containsKey("X-Custom-Api-Key")
                || reqHeaders.containsKey("X-Custom-Completions-Path");
        boolean hasCustomModel = "true".equalsIgnoreCase(reqHeaders.get("X-Custom-Model-Selected"));
        String structuredMode = reqHeaders.get("X-Structured-Output");
        boolean wantStructuredOutput = StringUtils.isNotBlank(structuredMode);

        if (!hasCustomModel && !hasCustomHeaders && !wantStructuredOutput) {
            return llmPrompt;
        }

        ChatOptions options = llmPrompt.getOptions();
        // The drawer owns tool execution; forcing response_format would suppress its tool_calls.
        boolean hasTools = options instanceof ToolCallingChatOptions tco
                && tco.getToolCallbacks() != null && !tco.getToolCallbacks().isEmpty();
        // Copy (never mutate) so a shared default-options instance can't leak state across requests.
        OpenAiChatOptions openAiOptions = toOpenAiOptions(options);

        if (hasCustomHeaders) {
            Map<String, String> existingHeaders = openAiOptions.getHttpHeaders() == null
                    ? new HashMap<>() : new HashMap<>(openAiOptions.getHttpHeaders());
            copyIfPresent(reqHeaders, existingHeaders, "X-Custom-Base-Url");
            copyIfPresent(reqHeaders, existingHeaders, "X-Custom-Api-Key");
            copyIfPresent(reqHeaders, existingHeaders, "X-Custom-Completions-Path");
            openAiOptions.setHttpHeaders(existingHeaders);
        }

        // 处理自定义模型：用户配置的模型优先级高于 ChatModelNode 中配置的默认模型
        if (hasCustomModel && llmRequest.model().isPresent()) {
            openAiOptions.setModel(llmRequest.model().get());
        }

        if (wantStructuredOutput && !hasTools) {
            openAiOptions.setResponseFormat(
                    buildResponseFormat(structuredMode, reqHeaders.get("X-Structured-Output-Schema")));
            // Carry the provider so MySpringAI can demote it if the endpoint rejects response_format.
            String providerId = reqHeaders.get("X-Provider");
            if (StringUtils.isNotBlank(providerId)) {
                Map<String, String> h = openAiOptions.getHttpHeaders() == null
                        ? new HashMap<>() : new HashMap<>(openAiOptions.getHttpHeaders());
                h.put("X-Provider", providerId);
                openAiOptions.setHttpHeaders(h);
            }
        }

        return new Prompt(llmPrompt.getInstructions(), openAiOptions);
    }

    private Map<String, String> extractRequestHeaders(LlmRequest llmRequest) {
        if (llmRequest.config().isPresent()
                && llmRequest.config().get().httpOptions().isPresent()
                && llmRequest.config().get().httpOptions().get().headers().isPresent()) {
            return llmRequest.config().get().httpOptions().get().headers().get();
        }
        return Map.of();
    }

    private void copyIfPresent(Map<String, String> from, Map<String, String> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    /** Convert to a fresh OpenAiChatOptions (copying, so shared defaults are never mutated). */
    private OpenAiChatOptions toOpenAiOptions(ChatOptions options) {
        if (options instanceof OpenAiChatOptions oa) {
            return oa.copy();
        }
        if (options instanceof ToolCallingChatOptions tc) {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
            List<ToolCallback> toolCallbacks = tc.getToolCallbacks();
            if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                builder.toolCallbacks(toolCallbacks);
            }
            java.util.Set<String> toolNames = tc.getToolNames();
            if (toolNames != null && !toolNames.isEmpty()) {
                builder.toolNames(toolNames);
            }
            if (tc.getTemperature() != null) {
                builder.temperature(tc.getTemperature());
            }
            if (tc.getMaxTokens() != null) {
                builder.maxTokens(tc.getMaxTokens());
            }
            if (tc.getTopP() != null) {
                builder.topP(tc.getTopP());
            }
            if (tc.getModel() != null) {
                builder.model(tc.getModel());
            }
            return builder.build();
        }
        return OpenAiChatOptions.builder().build();
    }

    private ResponseFormat buildResponseFormat(String mode, String schemaId) {
        if ("json_schema".equals(mode)) {
            String schema = StructuredOutputSchemas.get(schemaId);
            if (StringUtils.isNotBlank(schema)) {
                return ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder()
                                .name(schemaId).schema(schema).strict(true).build())
                        .build();
            }
            // Schema not registered -> degrade to json_object rather than fail the request.
        }
        return ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
    }

    /**
     * Rebuild the message list so each ADK functionResponse becomes a Spring AI ToolResponseMessage
     * (role=tool) carrying the originating tool_call_id. Returns null when the message count does not
     * line up 1:1 with the request contents, so we never risk corrupting an unexpected layout.
     */
    private List<Message> repairToolResponseMessages(LlmRequest llmRequest, List<Message> messages) {
        List<Content> contents = llmRequest.contents();
        if (contents == null || messages == null) {
            return null;
        }
        // Leading messages (e.g. the system prompt) come from config, not contents, so the
        // content-derived messages are the tail of the list. Align by that offset.
        int offset = messages.size() - contents.size();
        if (offset < 0) {
            return null;
        }
        boolean changed = false;
        List<Message> result = new ArrayList<>(messages);
        for (int i = 0; i < contents.size(); i++) {
            List<FunctionResponse> functionResponses = new ArrayList<>();
            for (Part part : contents.get(i).parts().orElse(List.of())) {
                part.functionResponse().ifPresent(functionResponses::add);
            }
            if (functionResponses.isEmpty()) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (FunctionResponse functionResponse : functionResponses) {
                String name = functionResponse.name().orElse("");
                String id = functionResponse.id().orElse(name);
                String data = toJson(functionResponse.response().orElse(Map.of()));
                responses.add(new ToolResponseMessage.ToolResponse(id, name, data));
            }
            result.set(offset + i, new ToolResponseMessage(responses));
            changed = true;
        }
        return changed ? result : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

}
