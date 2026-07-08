package org.zipp.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import javax.annotation.Resource;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Service("agentUsageTelemetryPlugin")
public class AgentUsageTelemetryPlugin extends BasePlugin {

    private final ConcurrentMap<String, ConcurrentLinkedDeque<LlmCallStart>> pendingLlmCalls = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ToolCallStart> pendingToolCalls = new ConcurrentHashMap<>();

    @Resource
    private AgentUsageTelemetryService agentUsageTelemetryService;

    public AgentUsageTelemetryPlugin() {
        super("AgentUsageTelemetryPlugin");
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
        if (invocationContext != null && invocationContext.session() != null) {
            AgentUsageTelemetryContext.registerInvocation(
                    invocationContext.invocationId(),
                    invocationContext.session().state());
        }
        return super.beforeRunCallback(invocationContext);
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
        if (invocationContext != null) {
            AgentUsageTelemetryContext.clearInvocation(invocationContext.invocationId());
        }
        return super.afterRunCallback(invocationContext);
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        resolveContext(context).ifPresent(runContext -> pendingLlmCalls
                .computeIfAbsent(context.invocationId(), ignored -> new ConcurrentLinkedDeque<>())
                .addLast(new LlmCallStart(
                        System.nanoTime(),
                        runContext,
                        phaseFromAgent(context.agentName(), runContext.phase()),
                        modelFromRequest(requestBuilder, runContext.model()))));
        return super.beforeModelCallback(context, requestBuilder);
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext context, LlmResponse response) {
        LlmCallStart start = pollLlmStart(context.invocationId());
        if (start != null) {
            GenerateContentResponseUsageMetadata usage = response == null
                    ? null
                    : response.usageMetadata().orElse(null);
            telemetryService().recordLlmCall(
                    start.runContext(),
                    start.phase(),
                    start.runContext().provider(),
                    StringUtils.defaultIfBlank(
                            response == null ? null : response.modelVersion().orElse(null),
                            start.model()),
                    elapsedMs(start.startedNanos()),
                    usage == null ? null : usage.promptTokenCount().orElse(null),
                    usage == null ? null : usage.candidatesTokenCount().orElse(null),
                    usage == null ? null : usage.totalTokenCount().orElse(null),
                    response != null && response.errorMessage().isPresent()
                            ? new IllegalStateException("model_error")
                            : null);
        }
        return super.afterModelCallback(context, response);
    }

    @Override
    public Maybe<LlmResponse> onModelErrorCallback(CallbackContext context,
                                                   LlmRequest.Builder requestBuilder,
                                                   Throwable error) {
        LlmCallStart start = pollLlmStart(context.invocationId());
        AgentUsageTelemetryContext.RunContext runContext = start == null
                ? resolveContext(context).orElse(null)
                : start.runContext();
        if (runContext != null) {
            telemetryService().recordLlmCall(
                    runContext,
                    start == null ? phaseFromAgent(context.agentName(), runContext.phase()) : start.phase(),
                    runContext.provider(),
                    start == null ? modelFromRequest(requestBuilder, runContext.model()) : start.model(),
                    start == null ? null : elapsedMs(start.startedNanos()),
                    null,
                    null,
                    null,
                    error);
        }
        return super.onModelErrorCallback(context, requestBuilder, error);
    }

    @Override
    public Maybe<java.util.Map<String, Object>> beforeToolCallback(BaseTool tool,
                                                                   java.util.Map<String, Object> args,
                                                                   ToolContext toolContext) {
        resolveContext(toolContext).ifPresent(runContext -> pendingToolCalls.put(
                toolKey(tool, toolContext),
                new ToolCallStart(System.nanoTime(), runContext, phaseFromAgent(toolContext.agentName(), runContext.phase()))));
        return super.beforeToolCallback(tool, args, toolContext);
    }

    @Override
    public Maybe<java.util.Map<String, Object>> afterToolCallback(BaseTool tool,
                                                                  java.util.Map<String, Object> args,
                                                                  ToolContext toolContext,
                                                                  java.util.Map<String, Object> result) {
        recordTool(tool, toolContext, null);
        return super.afterToolCallback(tool, args, toolContext, result);
    }

    @Override
    public Maybe<java.util.Map<String, Object>> onToolErrorCallback(BaseTool tool,
                                                                    java.util.Map<String, Object> args,
                                                                    ToolContext toolContext,
                                                                    Throwable error) {
        recordTool(tool, toolContext, error);
        return super.onToolErrorCallback(tool, args, toolContext, error);
    }

    private void recordTool(BaseTool tool, ToolContext toolContext, Throwable error) {
        ToolCallStart start = pendingToolCalls.remove(toolKey(tool, toolContext));
        AgentUsageTelemetryContext.RunContext runContext = start == null
                ? resolveContext(toolContext).orElse(null)
                : start.runContext();
        if (runContext == null || tool == null) {
            return;
        }
        telemetryService().recordToolCall(
                runContext,
                start == null ? phaseFromAgent(toolContext.agentName(), runContext.phase()) : start.phase(),
                tool.name(),
                start == null ? null : elapsedMs(start.startedNanos()),
                error);
    }

    private Optional<AgentUsageTelemetryContext.RunContext> resolveContext(CallbackContext context) {
        return context == null
                ? AgentUsageTelemetryContext.current()
                : AgentUsageTelemetryContext.resolveInvocation(context.invocationId());
    }

    private LlmCallStart pollLlmStart(String invocationId) {
        ConcurrentLinkedDeque<LlmCallStart> starts = pendingLlmCalls.get(invocationId);
        return starts == null ? null : starts.pollFirst();
    }

    private String toolKey(BaseTool tool, ToolContext toolContext) {
        String callId = toolContext == null ? "" : toolContext.functionCallId().orElse("");
        String invocationId = toolContext == null ? "" : toolContext.invocationId();
        String toolName = tool == null ? "unknown" : tool.name();
        return invocationId + ":" + callId + ":" + toolName;
    }

    private String modelFromRequest(LlmRequest.Builder requestBuilder, String fallback) {
        try {
            return requestBuilder.build().model().orElse(fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private AgentUsageTelemetryService telemetryService() {
        return agentUsageTelemetryService == null
                ? new AgentUsageTelemetryService(null)
                : agentUsageTelemetryService;
    }

    private String phaseFromAgent(String agentName, String fallbackPhase) {
        String normalized = StringUtils.defaultString(agentName).toLowerCase();
        if (normalized.contains("intent") || normalized.contains("analyst") || normalized.contains("analysis")) {
            return "routing";
        }
        if (normalized.contains("quality") || normalized.contains("semantic")
                || normalized.contains("review") || normalized.contains("critic") || normalized.contains("check")) {
            return "review";
        }
        if (normalized.contains("repair") || normalized.contains("revision") || normalized.contains("revise")) {
            return "repair";
        }
        if (normalized.contains("draw") || normalized.contains("generator") || normalized.contains("render")) {
            return "drawing";
        }
        return StringUtils.defaultIfBlank(fallbackPhase, "model");
    }

    private record LlmCallStart(
            long startedNanos,
            AgentUsageTelemetryContext.RunContext runContext,
            String phase,
            String model
    ) {
    }

    private record ToolCallStart(
            long startedNanos,
            AgentUsageTelemetryContext.RunContext runContext,
            String phase
    ) {
    }
}
