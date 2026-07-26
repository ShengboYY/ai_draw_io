package org.zipp.ai.trigger.http.turn;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationReferenceResolver;
import org.zipp.ai.application.turn.ExplicitMemoryDecision;
import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.memory.MemoryProposalCommand;
import org.zipp.ai.application.memory.MemoryProposalService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.api.dto.ChatResponseDTO;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Feature-gated compatibility bridge for the real v1 product ingress.
 *
 * <p>The browser keeps its existing v1 contract while V2 is being canaried. The bridge only
 * claims requests whose durable migration policy selects V2; it never turns a Legacy assignment
 * into a V2 execution. Final UI data is read from durable canvas/message stores after the runner
 * reaches a terminal status, so a disconnected request cannot leave a fake in-memory result.</p>
 */
@Component
@ConditionalOnProperty(name = "turn-engine.http.v1-v2-bridge.enabled", havingValue = "true")
@ConditionalOnBean({
        TurnHttpDeliveryAdapter.class,
        TurnHttpControlAdapter.class,
        TurnAttemptExecutionRunner.class
})
public final class LegacyTurnV2IngressBridge {

    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final long POLL_INTERVAL_MILLIS = 100L;

    private final TurnHttpDeliveryAdapter delivery;
    private final TurnHttpControlAdapter control;
    private final ICanvasStateStore canvases;
    private final IDiagramConversationStore messages;
    private final ObjectProvider<MemoryProposalService> memoryProposals;
    private final long timeoutMillis;

    public LegacyTurnV2IngressBridge(
            TurnHttpDeliveryAdapter delivery,
            TurnHttpControlAdapter control,
            ICanvasStateStore canvases,
            IDiagramConversationStore messages,
            ObjectProvider<MemoryProposalService> memoryProposals,
            @Value("${turn-engine.http.v1-v2-bridge.timeout-millis:60000}") long timeoutMillis
    ) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.control = Objects.requireNonNull(control, "control");
        this.canvases = Objects.requireNonNull(canvases, "canvases");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.memoryProposals = Objects.requireNonNull(memoryProposals, "memoryProposals");
        if (timeoutMillis < 1_000L) {
            throw new IllegalArgumentException("timeoutMillis must be at least one second");
        }
        this.timeoutMillis = timeoutMillis;
    }

    public ChatDispatch chat(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId
    ) {
        BridgeResult result = execute(ownerKey, request, requestId, runId);
        return result.legacyHandoff()
                ? new ChatDispatch(false, null)
                : new ChatDispatch(true, toChatResponse(result));
    }

    public boolean stream(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId,
            ResponseBodyEmitter emitter
    ) {
        try {
            BridgeResult result = execute(ownerKey, request, requestId, runId);
            if (result.legacyHandoff()) {
                return false;
            }
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
            emitter.completeWithError(failure);
        }
        return true;
    }

    private BridgeResult execute(
            String ownerKey,
            ChatRequestDTO request,
            String requestId,
            String runId
    ) {
        AuthenticatedActor actor = new AuthenticatedActor(ownerKey, ownerKey);
        TurnHttpRequest canonical = canonicalRequest(request, requestId, runId);
        TurnHttpDeliveryResult deliveryResult = delivery.executeLegacySync(actor, request);
        TurnSubmission submission = deliveryResult.submission();
        if (submission instanceof TurnSubmission.LegacyHandoff) {
            return BridgeResult.forLegacyHandoff();
        }
        TurnKey key = submissionKey(submission);
        PersistedTurnOutcome terminal;
        if (submission instanceof TurnSubmission.ExecutionAccepted
                || submission instanceof TurnSubmission.AlreadyRunning) {
            terminal = awaitTerminalReplay(actor, request, canonical);
        } else if (submission instanceof TurnSubmission.TerminalReplay replay) {
            terminal = replay.outcome();
        } else {
            return new BridgeResult(null, null, submissionCode(submission));
        }
        proposeExplicitMemory(request, canonical, key, terminal);
        return terminalResult(ownerKey, canonical, terminal);
    }

    private void proposeExplicitMemory(
            ChatRequestDTO request,
            TurnHttpRequest canonical,
            TurnKey turn,
            PersistedTurnOutcome terminal
    ) {
        if (terminal.status() != TurnStatus.COMPLETED
                || request.getMemoryChartbookId() == null || request.getMemoryChartbookId().isBlank()) {
            return;
        }
        ExplicitMemoryDecision.fromUserContent(canonical.content()).ifPresent(decision -> {
            MemoryProposalService service = memoryProposals.getIfAvailable();
            if (service == null) {
                return;
            }
            // Proposal failure must not rewrite a successfully committed turn response.
            service.propose(new MemoryProposalCommand(
                    turn,
                    request.getMemoryChartbookId(),
                    canonical.diagramId(),
                    decision.candidateId(turn),
                    decision.declarationDigest(),
                    decision.declaration(),
                    decision.decisionKey(),
                    decision.applicabilityStage(),
                    decision.canonicalText()));
        });
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
            TurnHttpRequest canonical
    ) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            TurnStatusQueryOutcome outcome = control.status(actor,
                    new TurnHttpControlRequest(
                            canonical.turnId(), canonical.conversationReference(), canonical.diagramId()));
            if (outcome instanceof TurnStatusQueryOutcome.Available available) {
                if (available.status().status() != TurnStatus.RUNNING
                        && available.status().status().isTerminal()) {
                    TurnSubmission replay = delivery.executeLegacySync(actor, request).submission();
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
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TURN_V2_BRIDGE_INTERRUPTED", interrupted);
            }
        }
        throw new IllegalStateException("TURN_V2_BRIDGE_TIMEOUT");
    }

    private BridgeResult terminalResult(
            String ownerKey,
            TurnHttpRequest canonical,
            PersistedTurnOutcome terminal
    ) {
        if (terminal.status() != TurnStatus.COMPLETED) {
            return new BridgeResult(null, null, terminal.terminalCode());
        }
        DiagramConversationMessage assistant = messages.listMessages(
                        ownerKey, canonical.diagramId(), canonical.conversationReference())
                .stream()
                .filter(message -> "agent".equalsIgnoreCase(message.getRole()))
                .filter(message -> canonical.turnId().equals(message.getTurnId()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (assistant == null) {
            return new BridgeResult(null, null, "TURN_TERMINAL_MESSAGE_UNAVAILABLE");
        }
        JSONObject payload = JSON.parseObject(terminal.terminalPayloadJson());
        Long canvasVersion = payload == null ? null : payload.getLong("canvasVersionAfter");
        if (canvasVersion == null) {
            return new BridgeResult(null, assistant.getContent(), null);
        }
        CanvasState canvas = canvases.find(ownerKey, canonical.diagramId()).orElse(null);
        if (canvas == null || canvas.getVersion() != canvasVersion) {
            // Never substitute a later canvas for the one committed by this turn.
            return new BridgeResult(null, null, "TURN_TERMINAL_CANVAS_UNAVAILABLE");
        }
        return new BridgeResult(canvas, assistant.getContent(), null);
    }

    private TurnHttpRequest canonicalRequest(ChatRequestDTO request, String requestId, String runId) {
        String session = request.getSessionId();
        String conversation = session == null || session.isBlank()
                ? ConversationReferenceResolver.DEFAULT_REFERENCE
                : "legacy:" + session;
        String turnId = requestId == null || requestId.isBlank() ? runId : requestId;
        String clientMessageId = request.getResponseMessageId();
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
                request.getSelectedLibraryVersionIds());
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
        emitter.send(JSON.toJSONString(event) + "\n", NDJSON);
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

    public record ChatDispatch(boolean handled, ChatResponseDTO response) {
    }

    private record BridgeResult(
            CanvasState canvas,
            String assistantMessage,
            String errorCode,
            boolean legacyHandoff
    ) {

        private BridgeResult(CanvasState canvas, String assistantMessage, String errorCode) {
            this(canvas, assistantMessage, errorCode, false);
        }

        private static BridgeResult forLegacyHandoff() {
            return new BridgeResult(null, null, null, true);
        }
    }
}
