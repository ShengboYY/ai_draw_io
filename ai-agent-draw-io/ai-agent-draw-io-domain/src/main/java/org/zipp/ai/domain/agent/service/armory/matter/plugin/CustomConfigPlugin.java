package org.zipp.ai.domain.agent.service.armory.matter.plugin;

import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.zipp.ai.domain.agent.service.chat.ProviderCatalog;
import org.zipp.ai.domain.agent.service.chat.StructuredOutputTier;
import org.zipp.ai.domain.agent.service.intent.IntentRoutingContract;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service("customConfigPlugin")
public class CustomConfigPlugin extends BasePlugin {

    public CustomConfigPlugin() {
        super("CustomConfigPlugin");
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        String sessionId = context.sessionId();
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.getConfig(sessionId);

        if (config != null) {
            GenerateContentConfig.Builder configBuilder;
            if (requestBuilder.config().isPresent()) {
                configBuilder = requestBuilder.config().get().toBuilder();
            } else {
                configBuilder = GenerateContentConfig.builder();
            }

            HttpOptions.Builder httpOptionsBuilder;
            if (configBuilder.build().httpOptions().isPresent()) {
                httpOptionsBuilder = configBuilder.build().httpOptions().get().toBuilder();
            } else {
                httpOptionsBuilder = HttpOptions.builder();
            }

            Map<String, String> headers = new HashMap<>();
            if (httpOptionsBuilder.build().headers().isPresent()) {
                headers.putAll(httpOptionsBuilder.build().headers().get());
            }

            // 判断是否有任何自定义配置（baseUrl、apiKey、completionsPath 中至少一个非空，或用户主动选了自定义模型）
            boolean hasCustomBaseUrl = StringUtils.isNotBlank(config.getBaseUrl());
            boolean hasCustomApiKey = StringUtils.isNotBlank(config.getApiKey());
            boolean hasCustomCompletionsPath = StringUtils.isNotBlank(config.getCompletionsPath());

            if (hasCustomBaseUrl) {
                headers.put("X-Custom-Base-Url", config.getBaseUrl());
            }
            if (hasCustomApiKey) {
                headers.put("X-Custom-Api-Key", config.getApiKey());
            }
            if (hasCustomCompletionsPath) {
                headers.put("X-Custom-Completions-Path", config.getCompletionsPath());
            }

            // 关键：只有当用户主动选择了自定义模型时才设置 requestBuilder.model()
            // config.isCustomModelSelected() 由 Controller 层根据前端传入的 customModel 字段是否非空来设置
            // 这样可以区分「用户选默认模型」和「用户选自定义模型」两种情况
            if (config.isCustomModelSelected() && StringUtils.isNotBlank(config.getModel())) {
                requestBuilder.model(config.getModel());
                // 同时在 headers 中传递标记，供 MyMessageConverter 判断
                headers.put("X-Custom-Model-Selected", "true");
            }

            // Structured-output signalling: pick the strongest response_format this provider supports,
            // and (for the intent router only) point at its json_schema. The converter still gates on
            // "no tools present" so the drawer is never forced into JSON. See ProviderCatalog tiers.
            applyStructuredOutputHeaders(context, config, headers);

            httpOptionsBuilder.headers(headers);
            configBuilder.httpOptions(httpOptionsBuilder.build());
            requestBuilder.config(configBuilder.build());
        }

        return super.beforeModelCallback(context, requestBuilder);
    }

    // Agents that own tool execution (the drawer) must keep the content channel free for tool_calls,
    // so they are never given a response_format. Only the JSON-emitting agents are.
    private static final String DRAWER_AGENT = "agent_drawer";
    private static final String INTENT_ROUTER_AGENT = "agent_intent";
    private static final String STRUCTURED_OUTPUT_SCOPE_HEADER = "X-Structured-Output-Scope";

    private void applyStructuredOutputHeaders(CallbackContext context,
                                              CustomApiConfigManager.CustomApiConfig config,
                                              Map<String, String> headers) {
        String agentName = context == null ? null : context.agentName();
        if (DRAWER_AGENT.equals(agentName)) {
            return; // drawer uses tool calling; never force JSON output.
        }
        String scope = structuredOutputScope(config);
        StructuredOutputTier tier = ProviderCatalog.tierFor(config.getProvider(), scope);
        if (tier == StructuredOutputTier.NONE) {
            return; // provider can't enforce it (or unknown) -> rely on parser + validation.
        }
        if (INTENT_ROUTER_AGENT.equals(agentName) && tier.atLeast(StructuredOutputTier.JSON_SCHEMA)) {
            headers.put("X-Structured-Output", "json_schema");
            headers.put("X-Structured-Output-Schema", IntentRoutingContract.routerSchemaId());
        } else {
            headers.put("X-Structured-Output", "json_object");
        }
        // Carried so MySpringAI can demote only this credential/endpoint if it rejects response_format.
        headers.put(STRUCTURED_OUTPUT_SCOPE_HEADER, scope);
        if (StringUtils.isNotBlank(config.getProvider())) {
            headers.put("X-Provider", config.getProvider());
        }
    }

    // Do not include secrets. Prefer credential id; otherwise use endpoint; finally platform default.
    private String structuredOutputScope(CustomApiConfigManager.CustomApiConfig config) {
        if (StringUtils.isNotBlank(config.getModelCredentialId())) {
            return "credential:" + config.getModelCredentialId().trim();
        }
        if (StringUtils.isNotBlank(config.getBaseUrl()) || StringUtils.isNotBlank(config.getCompletionsPath())) {
            return "endpoint:" + StringUtils.defaultString(config.getBaseUrl()).trim()
                    + "|" + StringUtils.defaultString(config.getCompletionsPath()).trim();
        }
        return "provider:" + StringUtils.defaultString(config.getProvider()).trim();
    }
}
