package org.zipp.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.JsonBaseModel;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.types.util.SecretLogSanitizer;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Service("agentUsageTelemetryPlugin")
public class AgentUsageTelemetryPlugin extends BasePlugin {

    private static final int MAX_STREAM_RESPONSE_CHUNKS = 64;
    private static final int MAX_STREAM_TEXT_LENGTH = 64_000;
    private static final Logger log = LoggerFactory.getLogger(AgentUsageTelemetryPlugin.class);
    private final ConcurrentMap<String, ConcurrentLinkedDeque<LlmCallStart>> pendingLlmCalls = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ToolCallStart> pendingToolCalls = new ConcurrentHashMap<>();

    @Resource
    private AgentUsageTelemetryService agentUsageTelemetryService;

    @Resource
    private AgentDebugTraceService agentDebugTraceService;

    public AgentUsageTelemetryPlugin() {
        super("AgentUsageTelemetryPlugin");
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
        if (invocationContext != null && invocationContext.session() != null) {
            java.util.Map<String, Object> state = invocationContext.session().state();
            AgentUsageTelemetryContext.registerInvocation(invocationContext.invocationId(), state);
            // Read-once: the invocation is now keyed in our own registry, so drop the correlation
            // token from ADK session state. Left in place it would linger for the whole conversation
            // (and be persisted verbatim by any non-in-memory SessionService). A "temp:" prefix is
            // not an option — ADK filters temp: keys out of session.state(), the very map we read
            // the token back from, which would break correlation entirely.
            if (state != null) {
                state.remove(AgentUsageTelemetryContext.INVOCATION_STATE_TOKEN_KEY);
            }
        }
        return super.beforeRunCallback(invocationContext);
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
        if (invocationContext != null) {
            String invocationId = invocationContext.invocationId();
            // A provider may terminate without a final model/tool callback; never retain partial streams past the run.
            pendingLlmCalls.remove(invocationId);
            pendingToolCalls.keySet().removeIf(key -> key.startsWith(invocationId + ":"));
            AgentUsageTelemetryContext.clearInvocation(invocationContext.invocationId());
        }
        return super.afterRunCallback(invocationContext);
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        resolveContext(context).ifPresent(runContext -> {
            String callId = telemetryService().newLlmCallId();
            LlmRequest request = buildRequest(requestBuilder);
            pendingLlmCalls.computeIfAbsent(context.invocationId(), ignored -> new ConcurrentLinkedDeque<>())
                    .addLast(new LlmCallStart(
                            callId,
                            System.nanoTime(),
                            runContext,
                            phaseFromAgent(context.agentName(), runContext.phase()),
                            request == null ? runContext.model() : request.model().orElse(runContext.model()),
                            new LlmResponseAccumulator()));
            captureSpanPayload(runContext, callId, "LLM_INPUT", "application/json", toJson(request));
        });
        return super.beforeModelCallback(context, requestBuilder);
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext context, LlmResponse response) {
        LlmCallStart start = peekLlmStart(context.invocationId());
        if (start != null) {
            if (response != null) {
                start.responses().add(response);
            }
            if (!isTerminalResponse(response)) {
                return super.afterModelCallback(context, response);
            }
            pollLlmStart(context.invocationId());
            LlmResponse finalResponse = start.responses().lastResponse().orElse(response);
            GenerateContentResponseUsageMetadata usage = lastResponseWithUsage(start.responses())
                    .flatMap(LlmResponse::usageMetadata)
                    .orElse(null);
            telemetryService().recordLlmCall(
                    start.callId(),
                    start.runContext(),
                    start.phase(),
                    start.runContext().provider(),
                    StringUtils.defaultIfBlank(
                            finalResponse == null ? null : finalResponse.modelVersion().orElse(null),
                            start.model()),
                    elapsedMs(start.startedNanos()),
                    usage == null ? null : usage.promptTokenCount().orElse(null),
                    usage == null ? null : usage.candidatesTokenCount().orElse(null),
                    usage == null ? null : usage.totalTokenCount().orElse(null),
                    finalResponse != null && finalResponse.errorMessage().isPresent()
                            ? new IllegalStateException("model_error")
                            : null);
            captureSpanPayload(start.runContext(), start.callId(), "LLM_OUTPUT", "application/json",
                    toJson(start.responses()));
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
            String callId = start == null ? telemetryService().newLlmCallId() : start.callId();
            telemetryService().recordLlmCall(
                    callId,
                    runContext,
                    start == null ? phaseFromAgent(context.agentName(), runContext.phase()) : start.phase(),
                    runContext.provider(),
                    start == null ? modelFromRequest(requestBuilder, runContext.model()) : start.model(),
                    start == null ? null : elapsedMs(start.startedNanos()),
                    null,
                    null,
                    null,
                    error);
            captureSpanPayload(runContext, callId, "LLM_OUTPUT", "application/json", errorJson(error));
        }
        return super.onModelErrorCallback(context, requestBuilder, error);
    }

    @Override
    public Maybe<java.util.Map<String, Object>> beforeToolCallback(BaseTool tool,
                                                                   java.util.Map<String, Object> args,
                                                                   ToolContext toolContext) {
        resolveContext(toolContext).ifPresent(runContext -> {
            String callId = telemetryService().newToolCallId();
            pendingToolCalls.put(
                    toolKey(tool, toolContext),
                    new ToolCallStart(callId, System.nanoTime(), runContext,
                            phaseFromAgent(toolContext.agentName(), runContext.phase())));
            captureSpanPayload(runContext, callId, "TOOL_INPUT", "application/json", mapJson(args));
        });
        return super.beforeToolCallback(tool, args, toolContext);
    }

    @Override
    public Maybe<java.util.Map<String, Object>> afterToolCallback(BaseTool tool,
                                                                  java.util.Map<String, Object> args,
                                                                  ToolContext toolContext,
                                                                  java.util.Map<String, Object> result) {
        recordTool(tool, toolContext, result, null);
        return super.afterToolCallback(tool, args, toolContext, result);
    }

    @Override
    public Maybe<java.util.Map<String, Object>> onToolErrorCallback(BaseTool tool,
                                                                    java.util.Map<String, Object> args,
                                                                    ToolContext toolContext,
                                                                    Throwable error) {
        recordTool(tool, toolContext, null, error);
        return super.onToolErrorCallback(tool, args, toolContext, error);
    }

    private void recordTool(BaseTool tool,
                            ToolContext toolContext,
                            java.util.Map<String, Object> result,
                            Throwable error) {
        ToolCallStart start = pendingToolCalls.remove(toolKey(tool, toolContext));
        AgentUsageTelemetryContext.RunContext runContext = start == null
                ? resolveContext(toolContext).orElse(null)
                : start.runContext();
        if (runContext == null || tool == null) {
            return;
        }
        String callId = start == null ? telemetryService().newToolCallId() : start.callId();
        telemetryService().recordToolCall(
                callId,
                runContext,
                start == null ? phaseFromAgent(toolContext.agentName(), runContext.phase()) : start.phase(),
                tool.name(),
                start == null ? null : elapsedMs(start.startedNanos()),
                error);
        captureSpanPayload(runContext, callId, "TOOL_OUTPUT", "application/json",
                error == null ? mapJson(result) : errorJson(error));
    }

    private Optional<AgentUsageTelemetryContext.RunContext> resolveContext(CallbackContext context) {
        return context == null
                ? AgentUsageTelemetryContext.current()
                : AgentUsageTelemetryContext.resolveInvocation(context.invocationId());
    }

    private LlmCallStart pollLlmStart(String invocationId) {
        ConcurrentLinkedDeque<LlmCallStart> starts = pendingLlmCalls.get(invocationId);
        if (starts == null) {
            return null;
        }
        LlmCallStart start = starts.pollFirst();
        if (starts.isEmpty()) {
            pendingLlmCalls.remove(invocationId, starts);
        }
        return start;
    }

    private LlmCallStart peekLlmStart(String invocationId) {
        ConcurrentLinkedDeque<LlmCallStart> starts = pendingLlmCalls.get(invocationId);
        return starts == null ? null : starts.peekFirst();
    }

    private boolean isTerminalResponse(LlmResponse response) {
        return response == null
                || !response.partial().orElse(false)
                || response.turnComplete().orElse(false)
                || response.finishReason().isPresent()
                || response.usageMetadata().isPresent()
                || response.errorMessage().isPresent();
    }

    private Optional<LlmResponse> lastResponseWithUsage(LlmResponseAccumulator responses) {
        if (responses == null) {
            return Optional.empty();
        }
        return responses.snapshot().stream()
                .filter(item -> item.usageMetadata().isPresent())
                .reduce((first, second) -> second);
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

    private LlmRequest buildRequest(LlmRequest.Builder requestBuilder) {
        try {
            return requestBuilder == null ? null : requestBuilder.build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toJson(JsonBaseModel value) {
        if (value == null) {
            return null;
        }
        try {
            return value.toJson();
        } catch (Exception e) {
            // Optional debug serialization must never abort an LLM callback.
            log.warn("Debug trace serialization failed", e);
            return null;
        }
    }

    private String toJson(LlmResponseAccumulator accumulator) {
        if (accumulator == null) {
            return null;
        }
        List<LlmResponse> responses = accumulator.snapshot();
        if (responses.isEmpty()) {
            return null;
        }
        if (responses.size() == 1 && accumulator.droppedChunkCount() == 0) {
            return toJson(responses.get(0));
        }
        try {
            List<Object> chunks = new ArrayList<>();
            Map<String, Object> aggregate = new LinkedHashMap<>();
            aggregate.put("streamed", true);
            aggregate.put("droppedChunkCount", accumulator.droppedChunkCount());
            for (LlmResponse response : responses) {
                String json = response.toJson();
                @SuppressWarnings("unchecked")
                Map<String, Object> chunk = JsonBaseModel.getMapper().readValue(json, Map.class);
                chunks.add(chunk);
                copyIfPresent(chunk, aggregate, "finishReason");
                copyIfPresent(chunk, aggregate, "usageMetadata");
                copyIfPresent(chunk, aggregate, "modelVersion");
                copyIfPresent(chunk, aggregate, "errorMessage");
            }
            aggregate.put("chunks", chunks);
            if (!accumulator.combinedText().isEmpty()) {
                aggregate.put("content", Map.of(
                        "role", "model",
                        "parts", List.of(Map.of("text", accumulator.combinedText()))));
            }
            return JsonBaseModel.getMapper().writeValueAsString(aggregate);
        } catch (Exception e) {
            // Preserve at least the final raw response if aggregation fails.
            log.warn("Streamed debug trace aggregation failed", e);
            return toJson(responses.get(responses.size() - 1));
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private String mapJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return JsonBaseModel.getMapper().writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String errorJson(Throwable error) {
        return error == null ? null : mapJson(Map.of(
                "errorClass", error.getClass().getName(),
                "message", StringUtils.defaultString(error.getMessage())));
    }

    private void captureSpanPayload(AgentUsageTelemetryContext.RunContext context,
                                    String spanId,
                                    String payloadKind,
                                    String contentType,
                                    String content) {
        if (agentDebugTraceService == null || context == null) {
            return;
        }
        try {
            // Sensitive model/tool content remains behind the existing debug-capture control.
            agentDebugTraceService.captureSpanPayload(
                    context.userId(), context.runId(), spanId, payloadKind, contentType, content);
        } catch (Exception e) {
            // Debug capture is best-effort and cannot alter model/tool execution.
            log.warn("Debug trace capture failed. userId:{} runId:{} spanId:{}",
                    SecretLogSanitizer.maskCapability(context.userId()), context.runId(), spanId, e);
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
            String callId,
            long startedNanos,
            AgentUsageTelemetryContext.RunContext runContext,
            String phase,
            String model,
            LlmResponseAccumulator responses
    ) {
    }

    private static final class LlmResponseAccumulator {
        private final ConcurrentLinkedDeque<LlmResponse> responses = new ConcurrentLinkedDeque<>();
        private final StringBuilder combinedText = new StringBuilder();
        private int droppedChunkCount;

        private synchronized void add(LlmResponse response) {
            if (response == null) {
                return;
            }
            responses.addLast(response);
            while (responses.size() > MAX_STREAM_RESPONSE_CHUNKS) {
                responses.pollFirst();
                droppedChunkCount++;
            }
            // Retain user-visible streamed text independently from the bounded raw-chunk window.
            response.content().ifPresent(content -> content.parts().orElse(List.of()).forEach(part ->
                    part.text().ifPresent(this::appendText)));
        }

        private void appendText(String text) {
            int remaining = MAX_STREAM_TEXT_LENGTH - combinedText.length();
            if (remaining > 0 && text != null) {
                combinedText.append(text, 0, Math.min(text.length(), remaining));
            }
        }

        private synchronized List<LlmResponse> snapshot() {
            return List.copyOf(responses);
        }

        private synchronized Optional<LlmResponse> lastResponse() {
            return Optional.ofNullable(responses.peekLast());
        }

        private synchronized String combinedText() {
            return combinedText.toString();
        }

        private synchronized int droppedChunkCount() {
            return droppedChunkCount;
        }
    }

    private record ToolCallStart(
            String callId,
            long startedNanos,
            AgentUsageTelemetryContext.RunContext runContext,
            String phase
    ) {
    }
}
