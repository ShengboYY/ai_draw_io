package org.zipp.ai.trigger.http.turn;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationReferenceResolver;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptCompletion;
import org.zipp.ai.application.turn.execution.TurnHandle;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTracePayloadKind;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.api.dto.ChatResponseDTO;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * V2-only adapter for the browser's existing chat wire contract.
 *
 * <p>The public URL and DTO remain stable while every accepted product turn is executed by V2.
 * A missing V2 runner or a retry pinned to a retired Legacy assignment fails closed instead of
 * invoking the old agent workflow. Final UI data is read from durable canvas/message stores after
 * the runner reaches a terminal status, so a disconnected request cannot invent an in-memory
 * result.</p>
 */
@Component
@ConditionalOnProperty(name = "turn-engine.http.product-v2-ingress.enabled", havingValue = "true")
public final class TurnV2ProductIngressAdapter {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final TurnHttpDeliveryAdapter delivery;
    private final TurnHttpControlAdapter control;
    private final ICanvasStateStore canvases;
    private final IDiagramConversationStore messages;
    private final ObjectProvider<TurnAttemptExecutionRunner> runner;
    private final AgentUsageTelemetryService telemetry;
    private final ObjectProvider<AgentDebugTraceService> debugTraces;
    private final DrawioToolCallRenderer previewRenderer = new DrawioToolCallRenderer();
    private final long timeoutMillis;

    public TurnV2ProductIngressAdapter(
            TurnHttpDeliveryAdapter delivery,
            TurnHttpControlAdapter control,
            ICanvasStateStore canvases,
            IDiagramConversationStore messages,
            ObjectProvider<TurnAttemptExecutionRunner> runner,
            AgentUsageTelemetryService telemetry,
            ObjectProvider<AgentDebugTraceService> debugTraces,
            @Value("${turn-engine.http.product-v2-ingress.timeout-millis:180000}") long timeoutMillis
    ) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.control = Objects.requireNonNull(control, "control");
        this.canvases = Objects.requireNonNull(canvases, "canvases");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.debugTraces = Objects.requireNonNull(debugTraces, "debugTraces");
        if (timeoutMillis < 1_000L) {
            throw new IllegalArgumentException("timeoutMillis must be at least one second");
        }
        this.timeoutMillis = timeoutMillis;
    }

    public ChatResponseDTO chat(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId
    ) {
        AgentUsageTelemetryService.RunScope run = startRun(
                ownerKey, request, requestId, runId, "chat");
        Throwable runFailure = null;
        try (AgentUsageTelemetryContext.Scope ignored =
                     AgentUsageTelemetryContext.bind(run.getContext())) {
            captureRunInput(run, request);
            ObservedBridgeResult observed = executeObserved(
                    ownerKey, request, requestId, runId, null);
            runFailure = observed.failure();
            captureRunOutput(run, observed.result());
            return toChatResponse(observed.result());
        } catch (Exception failure) {
            runFailure = failure;
            // Sync and streaming delivery expose the same bounded error vocabulary.
            BridgeResult failed = new BridgeResult(null, null, bridgeErrorCode(failure));
            captureRunOutput(run, failed);
            return toChatResponse(failed);
        } finally {
            completeRun(run, runFailure);
        }
    }

    public void stream(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId,
            ResponseBodyEmitter emitter
    ) {
        AgentUsageTelemetryService.RunScope run = startRun(
                ownerKey, request, requestId, runId, "chat_stream");
        Throwable runFailure = null;
        try (AgentUsageTelemetryContext.Scope ignored =
                     AgentUsageTelemetryContext.bind(run.getContext())) {
            captureRunInput(run, request);
            ObservedBridgeResult observed = executeObserved(
                    ownerKey, request, requestId, runId, emitter);
            BridgeResult result = observed.result();
            runFailure = observed.failure();
            captureRunOutput(run, result);
            if (result.errorCode() != null) {
                JSONObject chunk = new JSONObject();
                chunk.put("type", "error");
                chunk.put("content", result.errorCode());
                chunk.put("code", result.errorCode());
                send(emitter, "error", chunk);
            } else {
                JSONObject meta = new JSONObject();
                meta.put("type", "meta");
                meta.put("requestId", requestId);
                meta.put("runId", runId);
                send(emitter, "analyzing", meta);
                if (result.canvas() != null && hasCanvas(result.canvas().getCurrentXml())) {
                    CanvasState canvas = result.canvas();
                    JSONObject drawio = new JSONObject();
                    drawio.put("type", "drawio_done");
                    drawio.put("content", canvas.getCurrentXml());
                    drawio.put("diagramId", canvas.getDiagramId());
                    drawio.put("version", canvas.getVersion());
                    drawio.put("contentHash", canvas.getContentHash());
                    send(emitter, "done", drawio);
                }
                if (result.assistantMessage() != null && !result.assistantMessage().isBlank()) {
                    JSONObject answer = new JSONObject();
                    answer.put("type", "user");
                    answer.put("content", result.assistantMessage());
                    send(emitter, "answer", answer);
                }
                JSONObject done = new JSONObject();
                done.put("type", "done");
                send(emitter, "done", done);
            }
            emitter.complete();
        } catch (Exception failure) {
            runFailure = failure;
            // Keep transport failures inside the NDJSON contract instead of exposing Spring's
            // raw error JSON, which also prevents the browser from treating it as chat content.
            try {
                String errorCode = bridgeErrorCode(failure);
                captureRunOutput(run, new BridgeResult(null, null, errorCode));
                JSONObject chunk = new JSONObject();
                chunk.put("type", "error");
                chunk.put("content", errorCode);
                chunk.put("code", errorCode);
                send(emitter, "error", chunk);
                emitter.complete();
            } catch (IOException sendFailure) {
                runFailure = sendFailure;
                emitter.completeWithError(sendFailure);
            }
        } finally {
            completeRun(run, runFailure);
        }
    }

    private AgentUsageTelemetryService.RunScope startRun(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId,
            String requestType
    ) {
        // V2 model ports run fixed, tool-free platform agents and never install the caller's saved
        // credential, so the run and every LLM call it fathers must be attributed to the platform
        // key. Claiming USER_KEY here would bill the user's credential for calls it never made.
        return telemetry.startRun(
                runId,
                requestId,
                ownerKey,
                request == null ? null : request.getAgentId(),
                request == null ? null : request.getSessionId(),
                requestType,
                request == null ? null : request.getDiagramId(),
                AgentUsageTelemetryService.PLATFORM,
                null,
                "openai",
                "unknown");
    }

    private void captureRunInput(AgentUsageTelemetryService.RunScope run, ChatRequestDTO request) {
        if (run == null || request == null) {
            return;
        }
        capture(run.getContext(), DebugTracePayloadKind.INPUT, "text/plain", request.getMessage());
    }

    private void captureRunOutput(AgentUsageTelemetryService.RunScope run, BridgeResult result) {
        if (run == null || result == null) {
            return;
        }
        capture(run.getContext(), payloadKind(result), "application/json", outcomeJson(result));
    }

    private void captureStepInput(AgentUsageTelemetryService.StepScope step, ChatRequestDTO request) {
        if (step == null || request == null) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("diagramId", request.getDiagramId());
        payload.put("sessionId", request.getSessionId());
        payload.put("message", request.getMessage());
        payload.put("selectedLibraryVersionIds", request.getSelectedLibraryVersionIds());
        payload.put("memoryChartbookId", request.getMemoryChartbookId());
        capture(step.getStepContext(), DebugTracePayloadKind.INPUT,
                "application/json", payload.toJSONString());
    }

    private void captureStepOutcome(AgentUsageTelemetryService.StepScope step, BridgeResult result) {
        if (step == null || result == null) {
            return;
        }
        capture(step.getStepContext(), payloadKind(result), "application/json", outcomeJson(result));
    }

    private DebugTracePayloadKind payloadKind(BridgeResult result) {
        return result.errorCode() == null ? DebugTracePayloadKind.OUTPUT : DebugTracePayloadKind.ERROR;
    }

    private String outcomeJson(BridgeResult result) {
        JSONObject payload = new JSONObject();
        payload.put("engine", "V2");
        if (result.errorCode() != null) {
            payload.put("errorCode", result.errorCode());
            return payload.toJSONString();
        }
        payload.put("assistantMessage", result.assistantMessage());
        if (result.canvas() != null) {
            payload.put("diagramId", result.canvas().getDiagramId());
            payload.put("canvasVersion", result.canvas().getVersion());
            payload.put("canvasXml", result.canvas().getCurrentXml());
        }
        return payload.toJSONString();
    }

    /** Attaches a payload to the span the inspector lazy-loads for this row (run root or step). */
    private void capture(
            AgentUsageTelemetryContext.RunContext context,
            DebugTracePayloadKind kind,
            String contentType,
            String content
    ) {
        AgentDebugTraceService traces = debugTraces.getIfAvailable();
        if (traces == null || context == null || content == null) {
            return;
        }
        try {
            traces.captureSpanPayload(context.userId(), context.runId(), context.spanId(),
                    kind, contentType, content);
        } catch (RuntimeException ignored) {
            // Debug capture is observability only; it must never alter the product chat result.
        }
    }

    private ObservedBridgeResult executeObserved(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId,
            ResponseBodyEmitter progressEmitter
    ) {
        AgentUsageTelemetryService.StepScope step = telemetry.startStep("turn_v2_execution");
        Throwable stepFailure = null;
        try (AgentUsageTelemetryContext.Scope ignored =
                     AgentUsageTelemetryContext.bind(step.getStepContext())) {
            telemetry.recordTraceEvent(
                    "turn_v2_started", "turn_v2_execution", "RUNNING", Map.of("engine", "V2"));
            captureStepInput(step, request);
            // Product chat remains V2-only; the observability wrapper must not revive Legacy.
            BridgeResult result = isV2Ready()
                    ? execute(ownerKey, request, requestId, runId, progressEmitter)
                    : new BridgeResult(null, null, "TURN_V2_EXECUTION_NOT_READY");
            captureStepOutcome(step, result);
            if (result.errorCode() != null) {
                stepFailure = new IllegalStateException(result.errorCode());
            }
            telemetry.recordTraceEvent(
                    "turn_v2_completed",
                    "turn_v2_execution",
                    stepFailure == null ? "SUCCESS" : "FAILED",
                    result.errorCode() == null
                            ? Map.of("engine", "V2")
                            : Map.of("engine", "V2", "errorCode", result.errorCode()));
            return new ObservedBridgeResult(result, stepFailure);
        } catch (RuntimeException failure) {
            stepFailure = failure;
            telemetry.recordTraceEvent(
                    "turn_v2_completed",
                    "turn_v2_execution",
                    "FAILED",
                    Map.of("engine", "V2", "errorClass", failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            telemetry.completeStep(step, stepFailure);
        }
    }

    private void completeRun(AgentUsageTelemetryService.RunScope run, Throwable failure) {
        telemetry.completeRun(run, failure);
    }

    private boolean isV2Ready() {
        return runner.getIfAvailable() != null;
    }

    private String bridgeErrorCode(Exception failure) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("TURN_")) {
            return message;
        }
        if (message != null) {
            try {
                return TurnFailureCode.valueOf(message).name();
            } catch (IllegalArgumentException ignored) {
                // Arbitrary exception text is not safe to return over the product contract.
            }
        }
        return "TURN_V2_BRIDGE_FAILED";
    }

    private BridgeResult execute(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId,
            ResponseBodyEmitter progressEmitter
    ) {
        AuthenticatedActor actor = new AuthenticatedActor(ownerKey, ownerKey);
        TurnHttpRequest canonical = canonicalRequest(request, requestId, runId);
        TurnHttpDeliveryResult deliveryResult = progressEmitter == null
                ? delivery.executeProductSync(actor, request)
                : delivery.executeProductTracked(
                        actor, request, productProgressSink(progressEmitter));
        TurnSubmission submission = deliveryResult.submission();
        if (submission instanceof TurnSubmission.LegacyAssignmentPinned) {
            // The same request id may still be pinned to a pre-cutover assignment. Preserve its
            // durable audit row, but never execute it through the retired workflow.
            return new BridgeResult(null, null, "TURN_V2_LEGACY_ASSIGNMENT_RETIRED");
        }
        TurnKey key = submissionKey(submission);
        PersistedTurnOutcome terminal;
        if (submission instanceof TurnSubmission.ExecutionAccepted
                || submission instanceof TurnSubmission.AlreadyRunning) {
            if (progressEmitter != null) {
                sendAcceptedProgress(progressEmitter);
            }
            TurnHandle handle = deliveryResult.handle();
            if (submission instanceof TurnSubmission.ExecutionAccepted && handle == null) {
                throw new IllegalStateException("TURN_V2_EXECUTION_HANDLE_MISSING");
            }
            terminal = awaitTerminalReplay(actor, request, canonical, handle);
        } else if (submission instanceof TurnSubmission.TerminalReplay replay) {
            terminal = replay.outcome();
        } else {
            return new BridgeResult(null, null, submissionCode(submission));
        }
        return terminalResult(ownerKey, canonical, key, terminal);
    }

    private void sendAcceptedProgress(ResponseBodyEmitter emitter) {
        try {
            // Commit the NDJSON response promptly and give the UI a visible running phase while
            // the server-owned attempt continues through classification, preparation, and generation.
            JSONObject chunk = new JSONObject();
            chunk.put("type", "status");
            chunk.put("content", "TURN_V2_EXECUTION_ACCEPTED");
            send(emitter, "analyzing", chunk);
        } catch (IOException failure) {
            throw new IllegalStateException("TURN_V2_STREAM_SEND_FAILED", failure);
        }
    }

    private TurnEventSink productProgressSink(ResponseBodyEmitter emitter) {
        return new TurnEventSink() {
            private boolean detached;

            @Override
            public synchronized void publish(TurnEvent event) {
                if (detached || event == null) {
                    return;
                }
                try {
                    projectProgressEvent(emitter, event);
                } catch (Exception deliveryFailure) {
                    // Browser disconnects detach only this subscriber; the fenced attempt keeps
                    // running and remains queryable through its durable terminal status.
                    detached = true;
                }
            }
        };
    }

    private void projectProgressEvent(ResponseBodyEmitter emitter, TurnEvent event)
            throws IOException {
        if ("plain_agent_draft_preview".equals(event.type())) {
            for (JSONObject chunk : previewRenderer.renderDraftPreview(event.payload())) {
                send(emitter, "drawing", chunk);
            }
            return;
        }

        JSONObject progress = agentProgressChunk(event);
        if (progress != null) {
            send(emitter, progressPhase(event.type(), progress.getString("tool")), progress);
        }
    }

    private JSONObject agentProgressChunk(TurnEvent event) {
        String stage = switch (event.type()) {
            case "plain_agent_started" -> "agent_started";
            case "plain_agent_skills_loaded" -> "skills_loaded";
            case "plain_agent_decision_started" -> "decision_started";
            case "plain_agent_decision_completed" -> "decision_completed";
            case "plain_agent_tool_started" -> "tool_started";
            case "plain_agent_tool_completed" -> "tool_completed";
            case "plain_agent_visual_review_started" -> "visual_review_started";
            case "plain_agent_visual_review_completed" -> "visual_review_completed";
            case "plain_agent_candidate_submitted" -> "candidate_submitted";
            default -> null;
        };
        if (stage == null) {
            return null;
        }

        JSONObject chunk = new JSONObject();
        chunk.put("type", "agent_progress");
        chunk.put("stage", stage);
        if ("skills_loaded".equals(stage)) {
            chunk.put("skillCount", parseInt(event.payload()));
            return chunk;
        }
        if ("candidate_submitted".equals(stage)) {
            return chunk;
        }

        String[] fields = event.payload().split("\\t", -1);
        if (fields.length >= 6) {
            chunk.put("step", parseInt(fields[0]));
            chunk.put("action", fields[1]);
            chunk.put("tool", fields[2]);
            chunk.put("outcome", fields[3]);
            chunk.put("latencyMs", parseLong(fields[4]));
            chunk.put("issueCount", parseInt(fields[5]));
        }
        if (fields.length >= 8) {
            chunk.put("reviewSummary", decodeProgressText(fields[6]));
            List<String> feedback = decodeProgressText(fields[7]).lines()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(3)
                    .toList();
            chunk.put("reviewFeedback", feedback);
        }
        return chunk;
    }

    private String decodeProgressText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // A malformed optional display field must not hide the underlying progress event.
            return "";
        }
    }

    private String progressPhase(String eventType, String tool) {
        if (eventType.contains("visual_review")) {
            return "reviewing";
        }
        if (eventType.contains("tool")) {
            return "drawing";
        }
        if ("plain_agent_candidate_submitted".equals(eventType)) {
            return "reviewing";
        }
        return "thinking";
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private TurnKey submissionKey(TurnSubmission submission) {
        if (submission instanceof TurnSubmission.ExecutionAccepted accepted) return accepted.key();
        if (submission instanceof TurnSubmission.AlreadyRunning running) return running.status().key();
        if (submission instanceof TurnSubmission.TerminalReplay replay) return replay.key();
        throw new IllegalArgumentException("V2 submission key is unavailable");
    }

    private PersistedTurnOutcome awaitTerminalReplay(
            AuthenticatedActor actor,
            ChatRequestDTO request,
            TurnHttpRequest canonical,
            TurnHandle handle
    ) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            TurnStatusQueryOutcome outcome = control.status(actor,
                    new TurnHttpControlRequest(
                            canonical.turnId(), canonical.conversationReference(), canonical.diagramId()));
            if (outcome instanceof TurnStatusQueryOutcome.Available available) {
                if (available.status().status() != TurnStatus.RUNNING
                        && available.status().status().isTerminal()) {
                    TurnSubmission replay = delivery.executeProductSync(actor, request).submission();
                    if (replay instanceof TurnSubmission.TerminalReplay terminalReplay) {
                        return terminalReplay.outcome();
                    }
                    throw new IllegalStateException("TURN_TERMINAL_REPLAY_UNAVAILABLE");
                }
            } else if (outcome instanceof TurnStatusQueryOutcome.TerminalUnavailable unavailable) {
                throw new IllegalStateException(unavailable.code());
            } else {
                throw new IllegalStateException("TURN_STATUS_NOT_FOUND");
            }
            TurnAttemptCompletion completion = completed(handle);
            if (completion != null) {
                if (completion instanceof TurnAttemptCompletion.PersistedTerminal terminal) {
                    return terminal.outcome();
                }
                if (completion instanceof TurnAttemptCompletion.StatusOnly statusOnly) {
                    throw new IllegalStateException(statusOnly.code());
                }
                if (completion instanceof TurnAttemptCompletion.AttemptSelfAborted aborted) {
                    throw new IllegalStateException(aborted.code());
                }
                throw new IllegalStateException("TURN_ATTEMPT_OWNERSHIP_LOST");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TURN_V2_BRIDGE_INTERRUPTED", interrupted);
            }
        }
        throw new IllegalStateException("TURN_V2_BRIDGE_TIMEOUT");
    }

    private TurnAttemptCompletion completed(TurnHandle handle) {
        if (handle == null) {
            return null;
        }
        return handle.completion().toCompletableFuture().getNow(null);
    }

    private BridgeResult terminalResult(
            String ownerKey,
            TurnHttpRequest canonical,
            TurnKey key,
            PersistedTurnOutcome terminal
    ) {
        if (terminal.status() != TurnStatus.COMPLETED) {
            return new BridgeResult(null, null, terminal.terminalCode());
        }
        String assistantMessage = messages.findAssistantMessage(
                        ownerKey, canonical.diagramId(), key.canonicalConversationId(), key.turnId())
                .map(message -> message.getContent())
                .orElse(null);
        if (assistantMessage == null) {
            return new BridgeResult(null, null, "TURN_TERMINAL_MESSAGE_UNAVAILABLE");
        }
        JSONObject payload = JSON.parseObject(terminal.terminalPayloadJson());
        Long canvasVersion = payload == null ? null : payload.getLong("canvasVersionAfter");
        if (canvasVersion == null) {
            return new BridgeResult(null, assistantMessage, null);
        }
        CanvasState canvas = canvases.find(ownerKey, canonical.diagramId()).orElse(null);
        if (canvas == null || canvas.getVersion() != canvasVersion) {
            // Never substitute a later canvas for the one committed by this turn.
            return new BridgeResult(null, null, "TURN_TERMINAL_CANVAS_UNAVAILABLE");
        }
        return new BridgeResult(canvas, assistantMessage, null);
    }

    private TurnHttpRequest canonicalRequest(ChatRequestDTO request, String requestId, String runId) {
        String session = request.getSessionId();
        String conversation = session == null || session.isBlank()
                ? ConversationReferenceResolver.DEFAULT_REFERENCE
                : "legacy:" + session;
        String turnId = requestId == null || requestId.isBlank() ? runId : requestId;
        String clientMessageId = request.getClientMessageId();
        if (clientMessageId == null || clientMessageId.isBlank()) {
            // One-release fallback for callers that predate the explicit user-message field.
            clientMessageId = request.getResponseMessageId();
        }
        if (clientMessageId == null || clientMessageId.isBlank()) {
            clientMessageId = turnId;
        }
        return new TurnHttpRequest(
                turnId,
                conversation,
                required(request.getDiagramId(), "diagramId"),
                clientMessageId,
                required(request.getMessage(), "content"),
                session,
                request.getCurrentTurnAttachmentRefs(),
                null,
                request.getSelectedLibraryVersionIds(),
                request.getMemoryChartbookId(),
                request.getSkills());
    }

    private ChatResponseDTO toChatResponse(BridgeResult result) {
        ChatResponseDTO response = new ChatResponseDTO();
        if (result.errorCode() != null) {
            response.setType("error");
            response.setContent(result.errorCode());
            return response;
        }
        if (result.canvas() != null && hasCanvas(result.canvas().getCurrentXml())) {
            response.setType("drawio");
            response.setContent(result.canvas().getCurrentXml());
        } else {
            response.setType("user");
            response.setContent(result.assistantMessage() == null ? "" : result.assistantMessage());
        }
        return response;
    }

    private void send(ResponseBodyEmitter emitter, String phase, JSONObject chunk) throws IOException {
        JSONObject event = new JSONObject();
        event.put("phase", phase);
        event.put("chunk", chunk);
        // Progress originates on the attempt worker while terminal delivery uses this request
        // thread; serialize sends so two NDJSON lines can never interleave.
        synchronized (emitter) {
            emitter.send(JSON.toJSONString(event) + "\n", NDJSON);
        }
    }

    private boolean hasCanvas(String xml) {
        return xml != null && xml.contains("<mxGraphModel");
    }

    private String submissionCode(TurnSubmission submission) {
        if (submission instanceof TurnSubmission.NotReady notReady) return notReady.code();
        if (submission instanceof TurnSubmission.AdmissionRejected rejected) return rejected.code();
        if (submission instanceof TurnSubmission.IdempotencyConflict conflict) return conflict.code();
        if (submission instanceof TurnSubmission.LegacyRetryExpired expired) return expired.code();
        if (submission instanceof TurnSubmission.TerminalUnavailable unavailable) return unavailable.code();
        return "TURN_V2_SUBMISSION_REJECTED";
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record BridgeResult(
            CanvasState canvas,
            String assistantMessage,
            String errorCode
    ) {
    }

    private record ObservedBridgeResult(
            BridgeResult result,
            Throwable failure
    ) {
    }
}
