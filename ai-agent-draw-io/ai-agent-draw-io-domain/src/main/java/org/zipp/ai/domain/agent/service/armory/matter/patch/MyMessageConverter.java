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

        // 判断是否有自定义配置（通过 CustomConfigPlugin 传递的 headers 和 model）
        // hasCustomHeaders：检查是否有 X-Custom-Base-Url / X-Custom-Api-Key / X-Custom-Completions-Path
        // hasCustomModel：检查 X-Custom-Model-Selected header，这是 CustomConfigPlugin 在用户主动选择自定义模型时设置的标记
        //   不使用 llmRequest.model().isPresent() 判断，因为无法区分「ADK 内部设置的 model」和「用户自定义的 model」
        boolean hasCustomHeaders = llmRequest.config().isPresent() &&
                llmRequest.config().get().httpOptions().isPresent() &&
                llmRequest.config().get().httpOptions().get().headers().isPresent() &&
                (llmRequest.config().get().httpOptions().get().headers().get().containsKey("X-Custom-Base-Url") ||
                 llmRequest.config().get().httpOptions().get().headers().get().containsKey("X-Custom-Api-Key") ||
                 llmRequest.config().get().httpOptions().get().headers().get().containsKey("X-Custom-Completions-Path"));

        boolean hasCustomModel = llmRequest.config().isPresent() &&
                llmRequest.config().get().httpOptions().isPresent() &&
                llmRequest.config().get().httpOptions().get().headers().isPresent() &&
                "true".equalsIgnoreCase(llmRequest.config().get().httpOptions().get().headers().get().get("X-Custom-Model-Selected"));

        if (hasCustomModel || hasCustomHeaders) {
            ChatOptions options = llmPrompt.getOptions();
            OpenAiChatOptions openAiOptions;

            if (options instanceof OpenAiChatOptions) {
                // 已经是 OpenAiChatOptions，直接使用
                openAiOptions = (OpenAiChatOptions) options;
            } else if (options instanceof ToolCallingChatOptions) {
                // 从 ToolCallingChatOptions 转换为 OpenAiChatOptions，保留 tools
                OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
                ToolCallingChatOptions toolCallingChatOptions = (ToolCallingChatOptions) options;

                // 复制 toolCallbacks 和 toolNames，避免 tools 丢失
                List<ToolCallback> toolCallbacks = toolCallingChatOptions.getToolCallbacks();
                if (toolCallbacks != null && !toolCallbacks.isEmpty()) {
                    builder.toolCallbacks(toolCallbacks);
                }
                java.util.Set<String> toolNames = toolCallingChatOptions.getToolNames();
                if (toolNames != null && !toolNames.isEmpty()) {
                    builder.toolNames(toolNames);
                }

                // 复制其他参数
                if (toolCallingChatOptions.getTemperature() != null) {
                    builder.temperature(toolCallingChatOptions.getTemperature());
                }
                if (toolCallingChatOptions.getMaxTokens() != null) {
                    builder.maxTokens(toolCallingChatOptions.getMaxTokens());
                }
                if (toolCallingChatOptions.getTopP() != null) {
                    builder.topP(toolCallingChatOptions.getTopP());
                }
                if (toolCallingChatOptions.getModel() != null) {
                    builder.model(toolCallingChatOptions.getModel());
                }

                openAiOptions = builder.build();
            } else {
                openAiOptions = OpenAiChatOptions.builder().build();
            }

            // 处理自定义 HTTP Headers
            if (hasCustomHeaders) {
                Map<String, String> customHeaders = llmRequest.config().get().httpOptions().get().headers().get();
                Map<String, String> existingHeaders = openAiOptions.getHttpHeaders();
                if (existingHeaders == null) {
                    existingHeaders = new HashMap<>();
                } else {
                    existingHeaders = new HashMap<>(existingHeaders);
                }

                if (customHeaders.containsKey("X-Custom-Base-Url")) {
                    existingHeaders.put("X-Custom-Base-Url", customHeaders.get("X-Custom-Base-Url"));
                }
                if (customHeaders.containsKey("X-Custom-Api-Key")) {
                    existingHeaders.put("X-Custom-Api-Key", customHeaders.get("X-Custom-Api-Key"));
                }
                if (customHeaders.containsKey("X-Custom-Completions-Path")) {
                    existingHeaders.put("X-Custom-Completions-Path", customHeaders.get("X-Custom-Completions-Path"));
                }

                openAiOptions.setHttpHeaders(existingHeaders);
            }

            // 处理自定义模型：用户配置的模型优先级高于 ChatModelNode 中配置的默认模型
            if (hasCustomModel) {
                String customModel = llmRequest.model().get();
                openAiOptions.setModel(customModel);
            }

            // 返回一个新的 Prompt 以包含更新后的 options
            return new Prompt(llmPrompt.getInstructions(), openAiOptions);
        }

        return llmPrompt;
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
