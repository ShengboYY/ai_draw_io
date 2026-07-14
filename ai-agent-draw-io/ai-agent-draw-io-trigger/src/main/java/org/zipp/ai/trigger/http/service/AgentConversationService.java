package org.zipp.ai.trigger.http.service;

import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.api.dto.ChatResponseDTO;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSecret;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaExceededException;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.IModelCredentialService;
import org.zipp.ai.domain.account.service.PlatformDailyQuotaExceededException;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioMutationResultPostProcessor;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.skills.DrawioSkillAccessContext;
import org.zipp.ai.domain.agent.service.armory.matter.tool.DrawioToolAccessContext;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTracePayloadKind;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;
import org.zipp.ai.types.util.SecretLogSanitizer;
import com.alibaba.fastjson.JSON;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Slf4j
@Service
public class AgentConversationService {

    private static final int DEFAULT_MAX_DETERMINISTIC_REPAIR_ROUNDS = 1;
    private static final int MAX_DETERMINISTIC_REPAIR_ROUNDS = 3;
    private static final int MAX_VISUAL_REPAIR_ROUNDS = 1;
    private static final int MAX_BUFFERED_STREAM_CAPTURE_CHARS = 64_000;

    @Resource
    private IChatService chatService;

    @Resource
    private IIntentRoutingService intentRoutingService;

    @Resource
    private ICanvasAnalyzer canvasAnalyzer;

    @Resource
    private ICanvasVisualReviewer canvasVisualReviewer;

    @Resource
    private CanvasReviewImageValidator canvasReviewImageValidator;

    @Resource
    private ICanvasStateStore canvasStateStore;

    @Resource
    private DrawioPromptContextBuilder promptContextBuilder;

    @Resource
    private DrawioStreamResponseWriter streamResponseWriter;

    @Resource
    private SkillContentProvider skillContentProvider;

    @Resource
    private IModelCredentialService modelCredentialService;

    @Resource
    private AnonymousDemoQuotaService anonymousDemoQuotaService = new AnonymousDemoQuotaService();

    @Resource
    private VerifiedUserPlatformQuotaService verifiedUserPlatformQuotaService = new VerifiedUserPlatformQuotaService();

    @Resource
    private AgentUsageTelemetryService agentUsageTelemetryService;

    @Resource
    private AgentDebugTraceService agentDebugTraceService;

    @Resource
    private VisualReviewRolloutPolicy visualReviewRolloutPolicy;

    private final CanvasVisualReviewPolicy canvasVisualReviewPolicy = new CanvasVisualReviewPolicy();

    public ChatResponseDTO chat(ChatRequestDTO requestDTO) {
        AgentUsageTelemetryService.RunScope runScope = telemetryService().startRun(
                requestDTO.getRunId(), requestDTO.getRequestId(),
                requestDTO.getUserId(), requestDTO.getAgentId(), requestDTO.getSessionId(), "chat",
                requestDTO.getDiagramId(), credentialSource(requestDTO), requestDTO.getModelCredentialId(), "openai", "unknown");
        requestDTO.setRunId(runScope.getContext().runId());
        AgentUsageTelemetryContext.Scope initialScope = AgentUsageTelemetryContext.bind(runScope.getContext());
        AgentUsageTelemetryContext.Scope configuredScope = null;
        Throwable runError = null;
        String sessionId = null;
        try {
            recordLifecycleEvent(runScope, "HTTP_REQUEST_RECEIVED", "request", "SUCCESS",
                    requestMetadata(requestDTO, false));
            captureRunPayload(runScope, DebugTracePayloadKind.INPUT, requestDTO.getMessage());
            CustomApiConfigManager.CustomApiConfig config = buildCustomApiConfig(requestDTO);
            runScope = telemetryService().withProviderModel(runScope, config.getProvider(), config.getModel());
            configuredScope = AgentUsageTelemetryContext.bind(runScope.getContext());
            consumeAnonymousDemoQuota(requestDTO, config);
            consumeVerifiedUserPlatformQuota(requestDTO, config);
            sessionId = ensureSession(requestDTO);
            // Claim the reusable ADK session before installing any session-scoped configuration;
            // a concurrent request must not overwrite or clear another run's tool policy.
            DrawioToolAccessContext.openSession(sessionId, runScope.getContext().runId());
            CustomApiConfigManager.setConfig(sessionId, config);
            requestDTO = requestWithStoredCanvas(requestDTO);
            final ChatRequestDTO currentRequest = requestDTO;
            IntentRoutingResult routingResult = recordCapturedStep(
                    "routing", requestStepInput(currentRequest), AgentConversationService::routingStepOutput,
                    () -> routeIntent(currentRequest, config));
            recordRoutingDecision(runScope, routingResult);
            if (isReviewOnly(routingResult)) {
                ReviewOnlyContext reviewContext = prepareReviewOnlyContext(currentRequest, routingResult);
                ChatResponseDTO responseDTO = new ChatResponseDTO();
                if (!reviewContext.hasCanvas()) {
                    responseDTO.setType("user");
                    responseDTO.setContent(noCanvasReviewMessage(currentRequest.getMessage()));
                } else {
                    ReviewOnlyOutcome outcome = recordCapturedStep(
                            "visual_review", requestStepInput(currentRequest), value -> traceField("decision", value.decision().name()),
                            () -> reviewCurrentCanvas(currentRequest, routingResult, reviewContext));
                    responseDTO.setType("review_result");
                    responseDTO.setContent(outcome.content());
                }
                attachCorrelation(responseDTO, runScope);
                captureRunOutput(runScope, responseDTO, currentRequest.getDiagramId());
                return responseDTO;
            }
            if (routingResult.isDirectReply()) {
                ChatResponseDTO responseDTO = new ChatResponseDTO();
                responseDTO.setType("user");
                responseDTO.setContent(recordCapturedStep(
                        "direct_answer", routingStepOutput(routingResult), value -> traceField("answer", value),
                        () -> resolveDirectAnswer(routingResult)));
                attachCorrelation(responseDTO, runScope);
                captureRunOutput(runScope, responseDTO, currentRequest.getDiagramId());
                return responseDTO;
            }

            int maxDeterministicRepairRounds = effectiveDeterministicRepairRounds(currentRequest, routingResult);
            RoutedDrawMessage routedMessage = buildRoutedDrawMessage(currentRequest, routingResult, maxDeterministicRepairRounds, currentRequest.getUserId(), currentRequest.getSkills());
            captureDebugTrace(runScope, "ROUTED_MESSAGE", routedMessage.message());
            final String finalSessionId = sessionId;
            DrawioSkillAccessContext.bindSession(finalSessionId, routedMessage.allowedSkillNames());
            DrawioToolAccessContext.applyToolPolicy(
                    runScope.getContext().runId(), routedMessage.toolPolicy());
            ChatResponseDTO response = recordCapturedStep("drawing", traceField("message", routedMessage.message()), value -> value, () -> {
                List<String> messages = chatService.handleMessage(
                        currentRequest.getAgentId(),
                        currentRequest.getUserId(),
                        finalSessionId,
                        routedMessage.message(),
                        AgentUsageTelemetryContext.current().orElse(null));
                return parseChatResponse(messages);
            });
            attachCorrelation(response, runScope);
            captureRunOutput(runScope, response, currentRequest.getDiagramId());
            return response;
        } catch (Exception e) {
            runError = e;
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e);
        } finally {
            telemetryService().completeRun(runScope, runError);
            clearSessionConfig(sessionId, runScope.getContext().runId());
            if (configuredScope != null) {
                configuredScope.close();
            }
            initialScope.close();
        }
    }

    public void stream(ChatRequestDTO requestDTO, ResponseBodyEmitter emitter) {
        stream(requestDTO, emitter, null, "chat_stream");
    }

    public void streamVisualRepair(ChatRequestDTO requestDTO,
                                   String diagramType,
                                   boolean optimizeLayout,
                                   ResponseBodyEmitter emitter) {
        // The VLM policy already authorized a bounded repair; rerouting model-authored repair text
        // could turn it into a create action, so this internal continuation uses a fixed safe route.
        IntentRoutingResult repairRoute = new IntentRoutingResult();
        repairRoute.setRouteType(optimizeLayout ? "optimize_layout" : "edit_existing");
        repairRoute.setDiagramType(StringUtils.defaultIfBlank(diagramType, "none"));
        repairRoute.setSkillName("none");
        repairRoute.setReason("production_visual_review_repair");
        stream(requestDTO, emitter, repairRoute, "visual_repair_stream");
    }

    private void stream(ChatRequestDTO requestDTO,
                        ResponseBodyEmitter emitter,
                        IntentRoutingResult forcedRoutingResult,
                        String operation) {
        AgentUsageTelemetryService.RunScope runScope = telemetryService().startRun(
                requestDTO.getRunId(), requestDTO.getRequestId(),
                requestDTO.getUserId(), requestDTO.getAgentId(), requestDTO.getSessionId(), operation,
                requestDTO.getDiagramId(), credentialSource(requestDTO), requestDTO.getModelCredentialId(), "openai", "unknown");
        requestDTO.setRunId(runScope.getContext().runId());
        BoundedTextCapture streamOutputCapture = new BoundedTextCapture(MAX_BUFFERED_STREAM_CAPTURE_CHARS);
        AgentUsageTelemetryContext.Scope initialScope = AgentUsageTelemetryContext.bind(runScope.getContext());
        AgentUsageTelemetryContext.Scope configuredScope = null;
        AgentUsageTelemetryService.StepScope drawingStep = null;
        AtomicBoolean streamTelemetryCompleted = new AtomicBoolean(false);
        AtomicBoolean firstStreamOutputRecorded = new AtomicBoolean(false);
        long streamStartedNanos = System.nanoTime();
        String sessionId = null;
        try {
            recordLifecycleEvent(runScope, "HTTP_REQUEST_RECEIVED", "request", "SUCCESS",
                    requestMetadata(requestDTO, true));
            streamResponseWriter.sendMeta(emitter, runScope.getContext().requestId(), runScope.getContext().runId());
            recordLifecycleEvent(runScope, "STREAM_META_SENT", "stream", "SUCCESS",
                    Map.of("metaOnly", true));
            captureRunPayload(runScope, DebugTracePayloadKind.INPUT, requestDTO.getMessage());
            CustomApiConfigManager.CustomApiConfig config = buildCustomApiConfig(requestDTO);
            runScope = telemetryService().withProviderModel(runScope, config.getProvider(), config.getModel());
            configuredScope = AgentUsageTelemetryContext.bind(runScope.getContext());
            consumeAnonymousDemoQuota(requestDTO, config);
            consumeVerifiedUserPlatformQuota(requestDTO, config);
            sessionId = ensureSession(requestDTO);
            final String finalSessionId = sessionId;
            // Keep all session-scoped model, skill, and tool configuration owned by one run.
            DrawioToolAccessContext.openSession(finalSessionId, runScope.getContext().runId());
            CustomApiConfigManager.setConfig(finalSessionId, config);

            requestDTO = requestWithStoredCanvas(requestDTO);
            final ChatRequestDTO currentRequest = requestDTO;
            IntentRoutingResult routingResult = forcedRoutingResult == null
                    ? recordCapturedStep(
                    "routing", requestStepInput(currentRequest), AgentConversationService::routingStepOutput,
                    () -> routeIntent(currentRequest, config))
                    : forcedRoutingResult;
            recordRoutingDecision(runScope, routingResult);
            // The UI uses this compact event to label the visible Thinking timeline for the selected route.
            streamResponseWriter.sendRoute(emitter, routingResult.getRouteType());
            if (isReviewOnly(routingResult)) {
                try {
                    ReviewOnlyContext reviewContext = prepareReviewOnlyContext(currentRequest, routingResult);
                    if (!reviewContext.hasCanvas()) {
                        String content = noCanvasReviewMessage(currentRequest.getMessage());
                        captureRunOutput(runScope, "user", content, currentRequest.getDiagramId());
                        streamResponseWriter.sendDirectReply(emitter, content);
                    } else {
                        streamResponseWriter.sendVisualReviewStarted(
                                emitter, CanvasVisualReviewStage.CURRENT_CANVAS.name(), runScope.getContext().runId());
                        ReviewOnlyOutcome outcome = recordCapturedStep(
                                "visual_review", requestStepInput(currentRequest), value -> traceField("decision", value.decision().name()),
                                () -> reviewCurrentCanvas(currentRequest, routingResult, reviewContext));
                        captureRunOutput(runScope, "review_result", outcome.content(), currentRequest.getDiagramId());
                        streamResponseWriter.sendVisualReviewResult(
                                emitter,
                                CanvasVisualReviewStage.CURRENT_CANVAS.name(),
                                runScope.getContext().runId(),
                                outcome.content(),
                                outcome.analysis(),
                                outcome.result(),
                                outcome.decision());
                        streamResponseWriter.sendDone(emitter);
                        emitter.complete();
                    }
                    completeStreamTelemetry(streamTelemetryCompleted, null, runScope, null);
                } finally {
                    clearSessionConfig(finalSessionId, runScope.getContext().runId());
                }
                return;
            }
            if (routingResult.isDirectReply()) {
                try {
                    String answer = recordCapturedStep(
                            "direct_answer", routingStepOutput(routingResult), value -> traceField("answer", value),
                            () -> resolveDirectAnswer(routingResult));
                    captureRunOutput(runScope, "user", answer, currentRequest.getDiagramId());
                    streamResponseWriter.sendDirectReply(emitter, answer);
                    completeStreamTelemetry(streamTelemetryCompleted, null, runScope, null);
                } finally {
                    clearSessionConfig(finalSessionId, runScope.getContext().runId());
                }
                return;
            }

            // Each author has its own buffer because the ADK stream can interleave partial chunks.
            final ConcurrentHashMap<String, StringBuilder> authorBuffers = new ConcurrentHashMap<>();
            // Drawing-loop budget: one first draw plus N deterministic self-repair mutations.
            final int maxRepairRounds = forcedRoutingResult == null
                    ? effectiveDeterministicRepairRounds(requestDTO, routingResult)
                    : MAX_VISUAL_REPAIR_ROUNDS;
            final AtomicInteger mutationRounds = new AtomicInteger(0);
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final AtomicBoolean manuallyCompleted = new AtomicBoolean(false);
            final AtomicBoolean finalStreamTelemetryCompleted = streamTelemetryCompleted;
            final AtomicBoolean finalFirstStreamOutputRecorded = firstStreamOutputRecorded;
            final BoundedTextCapture finalStreamOutputCapture = streamOutputCapture;
            final long finalStreamStartedNanos = streamStartedNanos;
            final RoutedDrawMessage routedMessage = buildRoutedDrawMessage(currentRequest, routingResult, maxRepairRounds, currentRequest.getUserId(), currentRequest.getSkills());
            captureDebugTrace(runScope, "ROUTED_MESSAGE", routedMessage.message());
            // The current canvas travels in the request; keep it so patch_cells can merge a delta
            // without the model re-emitting the whole diagram.
            final String currentCanvasXml = contextBuilder().resolveCanvasXml(currentRequest);
            streamResponseWriter.setCurrentCanvas(emitter, currentCanvasXml);
            drawingStep = telemetryService().startStep("drawing");
            captureStepPayload(drawingStep, DebugTracePayloadKind.INPUT, traceField("message", routedMessage.message()));
            streamResponseWriter.setCanvasStateContext(
                    emitter,
                    currentRequest.getUserId(),
                    currentRequest.getDiagramId(),
                    currentRequest.getExpectedVersion(),
                    runScope.getContext().runId(),
                    drawingStep == null ? runScope.getContext().runId() : drawingStep.getStepContext().spanId());
            DrawioSkillAccessContext.bindSession(finalSessionId, routedMessage.allowedSkillNames());
            DrawioToolAccessContext.applyToolPolicy(
                    runScope.getContext().runId(), routedMessage.toolPolicy());
            final AgentUsageTelemetryService.RunScope finalRunScope = runScope;
            final AgentUsageTelemetryService.StepScope finalDrawingStep = drawingStep;

            Disposable disposable = chatService.handleMessageStream(
                            currentRequest.getAgentId(),
                            currentRequest.getUserId(),
                            finalSessionId,
                            routedMessage.message(),
                            finalDrawingStep != null
                                    ? finalDrawingStep.getStepContext()
                                    : runScope.getContext().withPhase("drawing"),
                            StringUtils.isBlank(currentCanvasXml)
                                    ? Map.of()
                                    : Map.of(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentCanvasXml))
                    .subscribe(
                            event -> {
                                try {
                                    String author = event.author();
                                    String phase = streamResponseWriter.resolvePhase(author);

                                    if (!event.functionResponses().isEmpty()) {
                                        if (processFunctionResponses(emitter, phase, event, currentCanvasXml)) {
                                            // Drawing loop control: the drawer self-repairs by reading each
                                            // mutation response's repairBrief in its own context. The stream
                                            // ends as soon as the canvas is clean or the mutation budget
                                            // (first draw + maxRepairRounds) is spent; otherwise ADK loops
                                            // and the model may call a repair tool again.
                                            MutationOutcome outcome = mutationOutcome(event);
                                            if (outcome != MutationOutcome.NONE) {
                                                int rounds = mutationRounds.incrementAndGet();
                                                recordLifecycleEvent(finalRunScope, "DRAWING_MUTATION", "drawing", "SUCCESS",
                                                        Map.of(
                                                                "round", rounds,
                                                                "retryCount", Math.max(0, rounds - 1),
                                                                "outcome", outcome.name()));
                                                boolean budgetSpent = rounds >= maxRepairRounds + 1;
                                                if (outcome == MutationOutcome.CLEAN || budgetSpent || maxRepairRounds == 0) {
                                                    flushAuthorBuffers(emitter, authorBuffers);
                                                    completeStream(emitter, manuallyCompleted, disposableRef, finalSessionId,
                                                            finalDrawingStep, finalRunScope, finalStreamTelemetryCompleted,
                                                            finalStreamOutputCapture);
                                                }
                                            }
                                            return;
                                        }
                                    }

                                    if (!event.functionCalls().isEmpty()) {
                                        return;
                                    }

                                    String content = event.stringifyContent();
                                    if (content == null || content.isEmpty()) {
                                        return;
                                    }
                                    appendStreamOutput(finalStreamOutputCapture, content);
                                    if (finalFirstStreamOutputRecorded.compareAndSet(false, true)) {
                                        recordLifecycleEvent(finalRunScope, "STREAM_FIRST_OUTPUT", "stream", "SUCCESS",
                                                Map.of("ttftMs", Math.max(0,
                                                        (System.nanoTime() - finalStreamStartedNanos) / 1_000_000L)));
                                    }

                                    boolean isPartial = event.partial().orElse(false);
                                    StringBuilder buffer = authorBuffers.computeIfAbsent(author, k -> new StringBuilder());
                                    String currentActiveLine = currentActiveLine(buffer).trim();
                                    boolean isLikelyJson = currentActiveLine.startsWith("{") || (currentActiveLine.isEmpty() && content.trim().startsWith("{"));

                                    if (!isLikelyJson) {
                                        streamResponseWriter.sendToken(emitter, phase, content);
                                    }

                                    buffer.append(content);
                                    String accumulated = buffer.toString();
                                    String bufferedDrawioXml = streamResponseWriter.extractDrawioXml(accumulated);
                                    if ("drawing".equals(phase) && StringUtils.isNotBlank(bufferedDrawioXml)) {
                                        // Some models emit multiline raw XML instead of strict NDJSON.
                                        buffer.setLength(0);
                                        streamResponseWriter.sendDrawioStream(emitter, phase, bufferedDrawioXml);
                                        return;
                                    }
                                    if ("drawing".equals(phase) && streamResponseWriter.isIncompleteDrawioXml(accumulated)) {
                                        return;
                                    }

                                    flushCompleteLines(emitter, phase, isPartial, buffer, accumulated, manuallyCompleted,
                                            disposableRef, finalSessionId, finalDrawingStep, finalRunScope,
                                            finalStreamTelemetryCompleted, finalStreamOutputCapture);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            error -> {
                                clearSessionConfig(finalSessionId, finalRunScope.getContext().runId());
                                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, error);
                                streamResponseWriter.handleStreamError(emitter, manuallyCompleted.get(), error);
                            },
                            () -> {
                                captureBufferedStreamOutput(finalRunScope, finalStreamOutputCapture);
                                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, null);
                                handleStreamComplete(
                                        emitter, authorBuffers, manuallyCompleted, finalSessionId, finalRunScope);
                            }
                    );
            disposableRef.set(disposable);
            if (manuallyCompleted.get() && !disposable.isDisposed()) {
                disposable.dispose();
            }

            emitter.onCompletion(() -> {
                clearSessionConfig(finalSessionId, finalRunScope.getContext().runId());
                captureBufferedStreamOutput(finalRunScope, finalStreamOutputCapture);
                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, null);
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onCompletion", disposable);
            });
            emitter.onTimeout(() -> {
                clearSessionConfig(finalSessionId, finalRunScope.getContext().runId());
                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope,
                        new IllegalStateException("stream_timeout"));
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onTimeout", disposable);
            });
            emitter.onError(e -> {
                clearSessionConfig(finalSessionId, finalRunScope.getContext().runId());
                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, e);
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onError", disposable);
            });
        } catch (AnonymousDemoQuotaExceededException e) {
            clearSessionConfig(sessionId, runScope.getContext().runId());
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            log.info("Anonymous demo quota exhausted for userId:{}", SecretLogSanitizer.maskCapability(requestDTO.getUserId()));
            try {
                streamResponseWriter.sendTypedError(emitter, e.getCode(), e.getInfo());
                streamResponseWriter.sendDone(emitter);
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (PlatformDailyQuotaExceededException e) {
            clearSessionConfig(sessionId, runScope.getContext().runId());
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            log.info("Verified user daily platform quota exhausted for userId:{}", SecretLogSanitizer.maskCapability(requestDTO.getUserId()));
            try {
                streamResponseWriter.sendTypedError(emitter, e.getCode(), e.getInfo());
                streamResponseWriter.sendDone(emitter);
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (AppException e) {
            clearSessionConfig(sessionId, runScope.getContext().runId());
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            log.info("Stream request rejected for userId:{} code:{}",
                    SecretLogSanitizer.maskCapability(requestDTO.getUserId()), e.getCode());
            try {
                streamResponseWriter.sendTypedError(emitter, e.getCode(), e.getInfo());
                streamResponseWriter.sendDone(emitter);
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (Exception e) {
            clearSessionConfig(sessionId, runScope.getContext().runId());
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        } finally {
            if (configuredScope != null) {
                configuredScope.close();
            }
            initialScope.close();
        }
    }

    private String ensureSession(ChatRequestDTO requestDTO) {
        // Validate the client-supplied sessionId; a stale id (e.g. after a backend restart that
        // wiped in-memory sessions) is transparently replaced with a fresh session.
        return chatService.ensureSession(requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId());
    }

    private ChatRequestDTO requestWithStoredCanvas(ChatRequestDTO requestDTO) {
        if (requestDTO == null || canvasStateStore == null || StringUtils.isBlank(requestDTO.getDiagramId())) {
            return requestDTO;
        }
        try {
            Optional<CanvasState> stored = canvasStateStore.find(requestDTO.getUserId(), requestDTO.getDiagramId());
            stored.ifPresent(state -> {
                if (StringUtils.isNotBlank(state.getCurrentXml())) {
                    requestDTO.setCanvasXml(state.getCurrentXml());
                }
                if (requestDTO.getExpectedVersion() == null) {
                    requestDTO.setExpectedVersion(state.getVersion());
                }
            });
        } catch (Exception e) {
            // Keep the existing canvasXml path as a compatibility fallback if persistence is unavailable.
            log.warn("Failed to load stored canvas. userId:{} diagramId:{}",
                    SecretLogSanitizer.maskCapability(requestDTO.getUserId()), logValue(requestDTO.getDiagramId()), e);
        }
        return requestDTO;
    }

    private ChatResponseDTO parseChatResponse(List<String> messages) {
        ChatResponseDTO responseDTO = new ChatResponseDTO();
        try {
            String result = messages.stream().reduce((first, second) -> second).orElse("");
            ChatResponseDTO parsed = JSON.parseObject(result, ChatResponseDTO.class);
            if (null != parsed) {
                responseDTO = parsed;
                if (null == responseDTO.getType()) {
                    responseDTO.setType("user");
                }
            } else {
                responseDTO.setType("user");
                responseDTO.setContent(String.join("\n", messages));
            }
        } catch (Exception e) {
            responseDTO.setType("user");
            responseDTO.setContent(String.join("\n", messages));
        }
        return responseDTO;
    }

    private int effectiveDeterministicRepairRounds(ChatRequestDTO requestDTO, IntentRoutingResult routingResult) {
        // Localized edits skip the review/revision loop entirely: with 0 iterations the stream
        // completes as soon as the edited canvas is flushed, instead of waiting on review rounds.
        if (routingResult != null
                && "edit_existing".equals(routingResult.getRouteType())
                && (StringUtils.isBlank(routingResult.getSkillName()) || "none".equals(routingResult.getSkillName()))) {
            return 0;
        }
        return normalizeDeterministicRepairRounds(requestedDeterministicRepairRounds(requestDTO));
    }

    private Integer requestedDeterministicRepairRounds(ChatRequestDTO requestDTO) {
        if (requestDTO.getMaxDeterministicRepairRounds() != null) {
            return requestDTO.getMaxDeterministicRepairRounds();
        }
        ChatRequestDTO.ClientHintsDTO hints = requestDTO.getClientHints();
        if (hints != null && hints.getMaxDeterministicRepairRounds() != null) {
            return hints.getMaxDeterministicRepairRounds();
        }
        if (requestDTO.getMaxReviewIterations() != null) {
            return requestDTO.getMaxReviewIterations();
        }
        return hints == null ? null : hints.getMaxReviewIterations();
    }

    private int normalizeDeterministicRepairRounds(Integer requestedRounds) {
        if (requestedRounds == null) {
            return DEFAULT_MAX_DETERMINISTIC_REPAIR_ROUNDS;
        }
        // Clamp client input so deterministic retries cannot become an unbounded model loop.
        return Math.max(0, Math.min(requestedRounds, MAX_DETERMINISTIC_REPAIR_ROUNDS));
    }

    private CustomApiConfigManager.CustomApiConfig buildCustomApiConfig(ChatRequestDTO requestDTO) {
        if (StringUtils.isNotBlank(requestDTO.getModelCredentialId())) {
            rejectRawCustomConfig(requestDTO, "Saved credential chat requests must not include raw custom model fields.");
            try {
                ModelCredentialSecret credential = modelCredentialService.resolveForChat(
                        requestDTO.getUserId(), requestDTO.getModelCredentialId());
                return CustomApiConfigManager.CustomApiConfig.builder()
                        .provider(credential.getProvider())
                        .baseUrl(credential.getBaseUrl())
                        .apiKey(credential.getApiKey())
                        .completionsPath(credential.getCompletionPath())
                        .model(credential.getModel())
                        .modelCredentialId(credential.getId())
                        .customModelSelected(true)
                        .build();
            } catch (IllegalArgumentException e) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage());
            }
        }
        rejectRawCustomConfig(requestDTO, "Custom model credentials must be saved before chat.");
        return CustomApiConfigManager.CustomApiConfig.builder()
                .provider("openai")
                .customModelSelected(false)
                .build();
    }

    private void rejectRawCustomConfig(ChatRequestDTO requestDTO, String message) {
        if (StringUtils.isNotBlank(requestDTO.getCustomBaseUrl())
                || StringUtils.isNotBlank(requestDTO.getCustomApiKey())
                || StringUtils.isNotBlank(requestDTO.getCustomCompletionsPath())
                || StringUtils.isNotBlank(requestDTO.getCustomModel())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), message);
        }
    }

    private void consumeAnonymousDemoQuota(ChatRequestDTO requestDTO, CustomApiConfigManager.CustomApiConfig config) {
        // Quota is consumed before intent routing because routing is already model work.
        demoQuotaService().consumeIfNeeded(requestDTO.getUserId(), config == null ? null : config.getApiKey());
    }

    private void consumeVerifiedUserPlatformQuota(ChatRequestDTO requestDTO, CustomApiConfigManager.CustomApiConfig config) {
        // User-owned API keys skip platform quota; blank custom keys still use the platform key.
        platformQuotaService().consumeIfNeeded(requestDTO.getUserId(), config == null ? null : config.getApiKey());
    }

    private AnonymousDemoQuotaService demoQuotaService() {
        return anonymousDemoQuotaService == null ? new AnonymousDemoQuotaService() : anonymousDemoQuotaService;
    }

    private VerifiedUserPlatformQuotaService platformQuotaService() {
        return verifiedUserPlatformQuotaService == null ? new VerifiedUserPlatformQuotaService() : verifiedUserPlatformQuotaService;
    }

    private AgentUsageTelemetryService telemetryService() {
        return agentUsageTelemetryService == null ? new AgentUsageTelemetryService(null) : agentUsageTelemetryService;
    }

    private void recordRoutingDecision(AgentUsageTelemetryService.RunScope runScope, IntentRoutingResult routingResult) {
        if (routingResult == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeType", StringUtils.defaultString(routingResult.getRouteType()));
        metadata.put("diagramType", StringUtils.defaultString(routingResult.getDiagramType()));
        metadata.put("hasSkill", StringUtils.isNotBlank(routingResult.getSkillName()));
        recordLifecycleEvent(runScope, "ROUTING_DECIDED", "routing", "SUCCESS", metadata);
    }

    private Map<String, Object> requestMetadata(ChatRequestDTO requestDTO, boolean stream) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("stream", stream);
        metadata.put("messageChars", textLength(requestDTO == null ? null : requestDTO.getMessage()));
        metadata.put("hasCanvasXml", StringUtils.isNotBlank(requestDTO == null ? null : requestDTO.getCanvasXml()));
        metadata.put("hasDiagramId", StringUtils.isNotBlank(requestDTO == null ? null : requestDTO.getDiagramId()));
        metadata.put("hasSavedCredential", StringUtils.isNotBlank(requestDTO == null ? null : requestDTO.getModelCredentialId()));
        return metadata;
    }

    private void recordStreamDone(AgentUsageTelemetryService.RunScope runScope, Throwable error) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("error", error != null);
        if (error != null) {
            metadata.put("errorClass", error.getClass().getSimpleName());
        }
        recordLifecycleEvent(runScope, "STREAM_DONE", "stream", error == null ? "SUCCESS" : "FAILED", metadata);
    }

    private void recordLifecycleEvent(AgentUsageTelemetryService.RunScope runScope,
                                      String eventType,
                                      String phase,
                                      String status,
                                      Map<String, ?> metadata) {
        if (runScope == null || runScope.getContext() == null) {
            return;
        }
        try {
            telemetryService().recordTraceEvent(runScope.getContext(), eventType, phase, status, metadata);
        } catch (Exception e) {
            // Trace lifecycle metadata is best-effort and must not change chat behavior.
            log.warn("Trace lifecycle event failed. userId:{} runId:{} eventType:{}",
                    SecretLogSanitizer.maskCapability(runScope.getContext().userId()),
                    runScope.getContext().runId(), eventType, e);
        }
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
    }

    private void captureDebugTrace(AgentUsageTelemetryService.RunScope runScope, String eventType, String content) {
        if (agentDebugTraceService == null || runScope == null || runScope.getContext() == null) {
            return;
        }
        try {
            agentDebugTraceService.capture(
                    runScope.getContext().userId(), runScope.getContext().runId(), eventType, content);
        } catch (Exception e) {
            // Debug capture must never alter the user-visible chat path.
            log.warn("Debug trace capture failed. userId:{} runId:{}",
                    SecretLogSanitizer.maskCapability(runScope.getContext().userId()), runScope.getContext().runId(), e);
        }
    }

    private void appendStreamOutput(BoundedTextCapture capture, String content) {
        if (capture == null || StringUtils.isEmpty(content)) {
            return;
        }
        capture.append(content);
    }

    private void captureBufferedStreamOutput(AgentUsageTelemetryService.RunScope runScope,
                                             BoundedTextCapture capture) {
        if (runScope == null || runScope.getContext() == null) {
            return;
        }
        BoundedTextSnapshot snapshot = capture == null ? null : capture.finish();
        if (snapshot != null) {
            captureRunOutput(runScope, "user", snapshot.content(), runScope.getContext().diagramId(),
                    snapshot.originalLength());
        }
    }

    private void captureRunOutput(AgentUsageTelemetryService.RunScope runScope,
                                  ChatResponseDTO response,
                                  String diagramId) {
        captureRunOutput(runScope,
                response == null ? null : response.getType(),
                response == null ? null : response.getContent(),
                diagramId);
    }

    private void captureRunOutput(AgentUsageTelemetryService.RunScope runScope,
                                  String type,
                                  String content,
                                  String diagramId) {
        captureRunOutput(runScope, type, content, diagramId, null);
    }

    private void captureRunOutput(AgentUsageTelemetryService.RunScope runScope,
                                  String type,
                                  String content,
                                  String diagramId,
                                  Long originalLength) {
        if (runScope == null || runScope.getContext() == null) {
            return;
        }
        // Keep the user-visible result and its final canvas correlation together for replay/audit views.
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", StringUtils.defaultIfBlank(type, "user"));
        output.put("content", content);
        output.put("diagramId", diagramId);
        output.put("requestId", runScope.getContext().requestId());
        output.put("runId", runScope.getContext().runId());
        String outputJson = serializeRunOutputWithinCaptureLimit(output, content);
        captureRunPayload(runScope, DebugTracePayloadKind.OUTPUT, "application/json", outputJson,
                originalLength == null ? outputJson.length() : originalLength);
    }

    private String serializeRunOutputWithinCaptureLimit(Map<String, Object> output, String content) {
        String json = JSON.toJSONString(output);
        if (json.length() <= MAX_BUFFERED_STREAM_CAPTURE_CHARS || content == null) {
            return json;
        }
        // Trim only the user-visible content field so retained structured output always remains valid JSON.
        int low = 0;
        int high = content.length();
        String best = JSON.toJSONString(Map.of());
        while (low <= high) {
            int middle = low + (high - low) / 2;
            output.put("content", content.substring(0, middle));
            String candidate = JSON.toJSONString(output);
            if (candidate.length() <= MAX_BUFFERED_STREAM_CAPTURE_CHARS) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private void captureRunPayload(AgentUsageTelemetryService.RunScope runScope,
                                   DebugTracePayloadKind payloadKind,
                                   String content) {
        captureRunPayload(runScope, payloadKind, "text/plain", content);
    }

    private void captureRunPayload(AgentUsageTelemetryService.RunScope runScope,
                                   DebugTracePayloadKind payloadKind,
                                   String contentType,
                                   String content) {
        captureRunPayload(runScope, payloadKind, contentType, content, content == null ? 0L : content.length());
    }

    private void captureRunPayload(AgentUsageTelemetryService.RunScope runScope,
                                   DebugTracePayloadKind payloadKind,
                                   String contentType,
                                   String content,
                                   long originalLength) {
        if (agentDebugTraceService == null || runScope == null || runScope.getContext() == null) {
            return;
        }
        try {
            // The run itself is the root span, so final streamed content attaches directly to it.
            agentDebugTraceService.captureSpanPayload(
                    runScope.getContext().userId(),
                    runScope.getContext().runId(),
                    runScope.getContext().runId(),
                    payloadKind,
                    contentType,
                    content,
                    originalLength);
        } catch (Exception e) {
            // Debug capture must never alter the user-visible chat path.
            log.warn("Debug span payload capture failed. userId:{} runId:{} payloadKind:{}",
                    SecretLogSanitizer.maskCapability(runScope.getContext().userId()),
                    runScope.getContext().runId(), payloadKind, e);
        }
    }

    private <T> T recordCapturedStep(String phase,
                                     Object input,
                                     Function<T, Object> outputMapper,
                                     Callable<T> action) throws Exception {
        AgentUsageTelemetryService.StepScope step = telemetryService().startStep(phase);
        AgentUsageTelemetryContext.Scope scope = step == null
                ? AgentUsageTelemetryContext.enterPhase(phase)
                : AgentUsageTelemetryContext.bind(step.getStepContext());
        captureStepPayload(step, DebugTracePayloadKind.INPUT, input);
        try (AgentUsageTelemetryContext.Scope ignored = scope) {
            T result = action.call();
            captureStepPayload(step, DebugTracePayloadKind.OUTPUT, outputMapper == null ? result : outputMapper.apply(result));
            telemetryService().completeStep(step, null);
            return result;
        } catch (Exception e) {
            captureStepPayload(step, DebugTracePayloadKind.ERROR, Map.of(
                    "status", "FAILED",
                    "errorClass", e.getClass().getSimpleName(),
                    "message", StringUtils.defaultString(e.getMessage())));
            telemetryService().completeStep(step, e);
            throw e;
        }
    }

    private void captureStepPayload(AgentUsageTelemetryService.StepScope step,
                                    DebugTracePayloadKind payloadKind,
                                    Object payload) {
        if (agentDebugTraceService == null || step == null || step.getStepContext() == null || payload == null) {
            return;
        }
        AgentUsageTelemetryContext.RunContext context = step.getStepContext();
        try {
            // Step payloads use the telemetry step id so the inspector can lazy-load the same span.
            agentDebugTraceService.captureSpanPayload(
                    context.userId(), context.runId(), context.spanId(), payloadKind,
                    "application/json", JSON.toJSONString(payload));
        } catch (Exception e) {
            log.warn("Debug step payload capture failed. userId:{} runId:{} spanId:{}",
                    SecretLogSanitizer.maskCapability(context.userId()), context.runId(), context.spanId(), e);
        }
    }

    // Trace payload maps may carry null values (e.g. an empty direct answer); Map.of would throw and
    // wrongly flip a successful step into a FAILED span, so use a null-tolerant single-field map.
    private static Map<String, Object> traceField(String key, Object value) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put(key, value);
        return field;
    }

    private Map<String, Object> requestStepInput(ChatRequestDTO requestDTO) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", requestDTO == null ? null : requestDTO.getMessage());
        input.put("diagramId", requestDTO == null ? null : requestDTO.getDiagramId());
        input.put("canvasSummary", requestDTO == null ? null : requestDTO.getCanvasSummary());
        input.put("hasCanvasXml", requestDTO != null && StringUtils.isNotBlank(requestDTO.getCanvasXml()));
        return input;
    }

    private static Map<String, Object> routingStepOutput(IntentRoutingResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("routeType", result == null ? null : result.getRouteType());
        output.put("diagramType", result == null ? null : result.getDiagramType());
        output.put("skillName", result == null ? null : result.getSkillName());
        return output;
    }

    private void attachCorrelation(ChatResponseDTO responseDTO, AgentUsageTelemetryService.RunScope runScope) {
        if (responseDTO == null || runScope == null || runScope.getContext() == null) {
            return;
        }
        responseDTO.setRequestId(runScope.getContext().requestId());
        responseDTO.setRunId(runScope.getContext().runId());
    }

    private String credentialSource(ChatRequestDTO requestDTO) {
        return requestDTO != null && StringUtils.isNotBlank(requestDTO.getModelCredentialId())
                ? AgentUsageTelemetryService.USER_KEY
                : AgentUsageTelemetryService.PLATFORM;
    }

    private IntentRoutingResult routeIntent(ChatRequestDTO requestDTO, CustomApiConfigManager.CustomApiConfig config) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        DrawioPromptContextBuilder contextBuilder = contextBuilder();
        String canvasXml = contextBuilder.resolveCanvasXml(requestDTO);
        return intentRoutingService.route(IntentRoutingCommand.builder()
                .userId(requestDTO.getUserId())
                .message(contextBuilder.buildIntentMessage(requestDTO))
                .canvasXml(canvasXml)
                .canvasSummary(contextBuilder.resolveCanvasSummary(requestDTO, canvasXml))
                .customApiConfig(config)
                .build());
    }

    private String resolveDirectAnswer(IntentRoutingResult routingResult) {
        return routingResult.getAnswer();
    }

    private boolean isReviewOnly(IntentRoutingResult routingResult) {
        return routingResult != null && "review_only".equals(routingResult.getRouteType());
    }

    private ReviewOnlyContext prepareReviewOnlyContext(ChatRequestDTO requestDTO,
                                                       IntentRoutingResult routingResult) {
        String canvasXml = contextBuilder().resolveCanvasXml(requestDTO);
        if (StringUtils.isBlank(canvasXml)) {
            return new ReviewOnlyContext(null, false);
        }
        CanvasAnalysis analysis = canvasAnalyzer.analyze(canvasXml, routingResult.getDiagramType());
        boolean hasCanvas = analysis != null && analysis.getSummary() != null
                && analysis.getSummary().getNodeCount() > 0;
        return new ReviewOnlyContext(analysis, hasCanvas);
    }

    private ReviewOnlyOutcome reviewCurrentCanvas(ChatRequestDTO requestDTO,
                                                   IntentRoutingResult routingResult,
                                                   ReviewOnlyContext context) {
        CanvasVisualReviewResult result;
        String imageDataUrl = requestDTO.getCanvasImageDataUrl();
        if (!visualReviewEnabled()) {
            result = CanvasVisualReviewResult.unavailable("feature_disabled");
        } else if (StringUtils.isBlank(imageDataUrl)
                || !CanvasVisualReviewOrchestrator.RENDERER_VERSION.equals(requestDTO.getCanvasImageRendererVersion())) {
            result = CanvasVisualReviewResult.unavailable("screenshot_missing");
        } else {
            try {
                canvasReviewImageValidator.validate(imageDataUrl);
                result = canvasVisualReviewer.review(CanvasVisualReviewCommand.builder()
                        .stage(CanvasVisualReviewStage.CURRENT_CANVAS)
                        .originalUserTask(requestDTO.getMessage())
                        .diagramType(routingResult.getDiagramType())
                        .afterImageDataUrl(imageDataUrl)
                        .analyzerEvidence(analyzerEvidence(context.analysis()))
                        .canvasSummary(context.analysis() == null || context.analysis().getSummary() == null
                                ? "" : context.analysis().getSummary().getSummary())
                        .languageHint(containsHanText(requestDTO.getMessage()) ? "zh" : "en")
                        .rendererVersion(requestDTO.getCanvasImageRendererVersion())
                        .expectedVersion(requestDTO.getExpectedVersion())
                        .build());
            } catch (IllegalArgumentException e) {
                result = CanvasVisualReviewResult.unavailable("invalid_screenshot");
            }
        }
        CanvasVisualReviewDecision decision = canvasVisualReviewPolicy.decide(
                result, CanvasVisualReviewStage.CURRENT_CANVAS, 0);
        return new ReviewOnlyOutcome(context.analysis(), result, decision,
                visualReviewContent(requestDTO.getMessage(), result));
    }

    private boolean visualReviewEnabled() {
        // Plain unit tests construct the service outside Spring; preserve the pre-rollout behavior there.
        return visualReviewRolloutPolicy == null || visualReviewRolloutPolicy.isEnabled();
    }

    private List<String> analyzerEvidence(CanvasAnalysis analysis) {
        if (analysis == null || analysis.getIssues() == null) return List.of();
        return analysis.getIssues().stream().limit(10)
                .map(CanvasAnalysisIssue::getMessage)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private String visualReviewContent(String userMessage, CanvasVisualReviewResult result) {
        if (result != null && result.isAvailable() && StringUtils.isNotBlank(result.getSummary())) {
            return result.getSummary();
        }
        return containsHanText(userMessage)
                ? "视觉审查暂不可用；画布未被修改，请稍后重试。"
                : "Visual review is currently unavailable. The canvas was not modified; please retry later.";
    }

    private String noCanvasReviewMessage(String userMessage) {
        return containsHanText(userMessage)
                ? "当前没有可审查的画布。"
                : "There is no drawable canvas to review.";
    }

    private boolean containsHanText(String value) {
        return StringUtils.defaultString(value).codePoints()
                .anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    // Inject skill rules only when the drawer needs diagram-specific semantics; small edits stay lean.
    // User-specified skills (if any) override the router's automatic selection.
    private SkillContentProvider.SkillSection skillSectionFor(IntentRoutingResult routingResult, String ownerId, List<String> userSkills) {
        String routeType = StringUtils.defaultString(routingResult.getRouteType());
        boolean generativeDraw = "create_new".equals(routeType)
                || "optimize_layout".equals(routeType)
                || ("edit_existing".equals(routeType)
                && StringUtils.isNotBlank(routingResult.getSkillName())
                && !"none".equals(routingResult.getSkillName()));
        if (!generativeDraw && (userSkills == null || userSkills.isEmpty())) {
            return SkillContentProvider.SkillSection.empty();
        }
        List<String> chosen = (userSkills != null && !userSkills.isEmpty())
                ? userSkills
                : List.of(StringUtils.defaultString(routingResult.getSkillName()));
        SkillContentProvider.SkillSection section = skillContentProvider.buildSkillSectionWithMetadata(chosen, ownerId);
        log.info("[skill-tools] routeType={} chosenSkills={} allowedSkillNames={} userSpecified={} ownerId={} sectionChars={}",
                routeType, chosen, section.requiredSkillNames(), userSkills != null && !userSkills.isEmpty(),
                SecretLogSanitizer.maskCapability(ownerId), section.text().length());
        return section;
    }

    private String buildRoutedMessage(ChatRequestDTO requestDTO,
                                      IntentRoutingResult routingResult,
                                      int maxDeterministicRepairRounds,
                                      String ownerId,
                                      List<String> userSkills) {
        return buildRoutedDrawMessage(requestDTO, routingResult, maxDeterministicRepairRounds, ownerId, userSkills).message();
    }

    private RoutedDrawMessage buildRoutedDrawMessage(ChatRequestDTO requestDTO,
                                                     IntentRoutingResult routingResult,
                                                     int maxDeterministicRepairRounds,
                                                     String ownerId,
                                                     List<String> userSkills) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        com.alibaba.fastjson.JSONObject routingJson = new com.alibaba.fastjson.JSONObject();
        routingJson.put("routeType", routingResult.getRouteType());
        routingJson.put("diagramType", routingResult.getDiagramType());
        routingJson.put("skillName", routingResult.getSkillName());
        routingJson.put("reason", routingResult.getReason());
        routingJson.put("maxRepairRounds", maxDeterministicRepairRounds);
        DrawioToolAccessContext.ToolPolicy toolPolicy = toolPolicyFor(routingResult);
        routingJson.put("allowedTools", toolPolicy.initialTools());
        routingJson.put("repairTools", toolPolicy.repairTools());
        routingJson.put("skillTools", DrawioSkillToolNames.SKILL_LOOKUP_TOOL_NAMES);
        routingJson.put("toolPolicy", "Use skillTools to load required skill rules before the initial draft. Use only allowedTools for the first canvas mutation. Self-repair rounds use only repairTools and must not call create_diagram; explicit user redraws route through a new create_diagram action.");
        // Log derived routing controls only; the routed message below can contain full canvas XML.
        log.info("[draw-route] userId={} routeType={} allowedTools={} repairTools={} maxRepairRounds={} skillName={}",
                SecretLogSanitizer.maskCapability(ownerId),
                logValue(routingResult.getRouteType()),
                toolPolicy.initialTools(),
                toolPolicy.repairTools(),
                maxDeterministicRepairRounds,
                logValue(routingResult.getSkillName()));

        SkillContentProvider.SkillSection skillSection = skillSectionFor(routingResult, ownerId, userSkills);
        String routedMessage = "[Intent Routing Result]\n"
                + routingJson.toJSONString()
                + "\n\n"
                + skillSection.text()
                + contextBuilder().buildDrawingContextMessage(requestDTO, routingResult);
        return new RoutedDrawMessage(
                routedMessage,
                skillSection.requiredSkillNames(),
                toolPolicy);
    }

    private record RoutedDrawMessage(String message,
                                     Set<String> allowedSkillNames,
                                     DrawioToolAccessContext.ToolPolicy toolPolicy) {
    }

    private record ReviewOnlyContext(CanvasAnalysis analysis, boolean hasCanvas) {
    }

    private record ReviewOnlyOutcome(CanvasAnalysis analysis,
                                     CanvasVisualReviewResult result,
                                     CanvasVisualReviewDecision decision,
                                     String content) {
    }

    private DrawioToolAccessContext.ToolPolicy toolPolicyFor(IntentRoutingResult routingResult) {
        String routeType = StringUtils.defaultString(routingResult.getRouteType());
        return switch (routeType) {
            case "create_new" -> phasedToolPolicy(DrawioCanvasToolNames.CREATE_DIAGRAM);
            case "edit_existing" -> phasedToolPolicy(DrawioCanvasToolNames.MODIFY_DIAGRAM);
            case "optimize_layout" -> phasedToolPolicy(DrawioCanvasToolNames.OPTIMIZE_DIAGRAM);
            case "review_only", "answer_only", "clarify" -> DrawioToolAccessContext.ToolPolicy.of(List.of(), List.of());
            default -> DrawioToolAccessContext.ToolPolicy.of(
                    DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES,
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM));
        };
    }

    private DrawioToolAccessContext.ToolPolicy phasedToolPolicy(String initialTool) {
        return DrawioToolAccessContext.ToolPolicy.of(
                List.of(initialTool),
                List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM));
    }

    private String buildIntentMessage(ChatRequestDTO requestDTO) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        return contextBuilder().buildIntentMessage(requestDTO);
    }

    private String buildDrawingContextMessage(ChatRequestDTO requestDTO) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        return contextBuilder().buildDrawingContextMessage(requestDTO, null);
    }

    private DrawioPromptContextBuilder contextBuilder() {
        return null == promptContextBuilder ? new DrawioPromptContextBuilder() : promptContextBuilder;
    }

    private String currentActiveLine(StringBuilder buffer) {
        int lastNewline = buffer.lastIndexOf("\n");
        if (lastNewline >= 0) {
            return buffer.substring(lastNewline + 1);
        }
        return buffer.toString();
    }

    private boolean processFunctionResponses(ResponseBodyEmitter emitter,
                                             String phase,
                                             com.google.adk.events.Event event,
                                             String currentCanvasXml) throws Exception {
        boolean processed = false;
        for (com.google.genai.types.FunctionResponse functionResponse : event.functionResponses()) {
            if (functionResponse.response().isEmpty()) {
                continue;
            }

            Object responseJson = com.alibaba.fastjson.JSON.toJSON(functionResponse.response().get());
            if (!(responseJson instanceof com.alibaba.fastjson.JSONObject json)) {
                continue;
            }

            String functionName = functionResponse.name().orElse(DrawioCanvasToolNames.CREATE_DIAGRAM);
            // Local patch responses return only the changed fragment; merge it into the canvas we already hold.
            if (DrawioCanvasToolNames.PATCH_CELLS.equals(functionName)
                    || DrawioCanvasToolNames.PATCH_CELLS.equals(json.getString("type"))) {
                String patchCells = json.getString("cells");
                boolean patchSent = streamResponseWriter.sendLocalCellPatch(emitter, phase, currentCanvasXml, patchCells);
                log.info("[diag-patch] {} canvasXmlChars={} cellsChars={} sent={}",
                        functionName,
                        null == currentCanvasXml ? -1 : currentCanvasXml.length(),
                        null == patchCells ? -1 : patchCells.length(),
                        patchSent);
                if (patchSent) {
                    return true;
                }
                processed = true;
                continue;
            }
            String content = json.getString("content");
            if (StringUtils.isNotBlank(content) && streamResponseWriter.supportsToolCall(functionName)) {
                com.alibaba.fastjson.JSONObject toolJson = new com.alibaba.fastjson.JSONObject();
                toolJson.put("type", functionName);
                toolJson.put("xml", content);
                json = toolJson;
            } else if (StringUtils.isBlank(json.getString("type"))) {
                continue;
            }

            if (streamResponseWriter.processAndSendLine(emitter, phase, json.toJSONString())) {
                return true;
            }
            processed = true;
        }
        return processed;
    }

    private void flushCompleteLines(ResponseBodyEmitter emitter,
                                    String phase,
                                    boolean isPartial,
                                    StringBuilder buffer,
                                    String accumulated,
                                    AtomicBoolean manuallyCompleted,
                                    AtomicReference<Disposable> disposableRef,
                                    String sessionId,
                                    AgentUsageTelemetryService.StepScope drawingStep,
                                    AgentUsageTelemetryService.RunScope runScope,
                                    AtomicBoolean streamTelemetryCompleted,
                                    BoundedTextCapture streamOutputCapture) throws Exception {
        if (!isPartial || accumulated.contains("\n")) {
            String[] lines = accumulated.split("\n", -1);
            String remaining = lines[lines.length - 1];

            buffer.setLength(0);
            if (!remaining.isEmpty()) {
                buffer.append(remaining);
            }

            int processUpTo = isPartial ? lines.length - 1 : lines.length;
            for (int i = 0; i < processUpTo; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (streamResponseWriter.processAndSendLine(emitter, phase, line)) {
                    completeStream(emitter, manuallyCompleted, disposableRef, sessionId,
                            drawingStep, runScope, streamTelemetryCompleted, streamOutputCapture);
                    return;
                }
            }
        }

        if (!isPartial) {
            String remaining = buffer.toString().trim();
            buffer.setLength(0);
            if (!remaining.isEmpty() && streamResponseWriter.processAndSendLine(emitter, phase, remaining)) {
                completeStream(emitter, manuallyCompleted, disposableRef, sessionId,
                        drawingStep, runScope, streamTelemetryCompleted, streamOutputCapture);
            }
        }
    }

    /** Request-local stream capture that retains a prefix while counting every emitted character. */
    private static final class BoundedTextCapture {
        private final int limit;
        private final StringBuilder retained = new StringBuilder();
        private long originalLength;
        private boolean finished;

        private BoundedTextCapture(int limit) {
            this.limit = Math.max(0, limit);
        }

        private synchronized void append(String text) {
            if (finished || text == null) {
                return;
            }
            originalLength += text.length();
            int remaining = limit - retained.length();
            if (remaining > 0) {
                retained.append(text, 0, Math.min(text.length(), remaining));
            }
        }

        private synchronized BoundedTextSnapshot finish() {
            if (finished) {
                return null;
            }
            finished = true;
            return new BoundedTextSnapshot(retained.toString(), originalLength);
        }
    }

    private record BoundedTextSnapshot(String content, long originalLength) {
    }

    /** Outcome of the mutation tool response(s) in one ADK event, for drawing-loop control. */
    private enum MutationOutcome {
        /** No drawing mutation in this event (e.g. a search tool response). */
        NONE,
        /** Canvas mutated and the deterministic analysis reports no blocking issues. */
        CLEAN,
        /** Canvas mutated but critical/major issues remain; the loop may continue. */
        NEEDS_REPAIR
    }

    private MutationOutcome mutationOutcome(com.google.adk.events.Event event) {
        MutationOutcome outcome = MutationOutcome.NONE;
        for (com.google.genai.types.FunctionResponse functionResponse : event.functionResponses()) {
            if (functionResponse.response().isEmpty()) {
                continue;
            }
            Object responseJson = com.alibaba.fastjson.JSON.toJSON(functionResponse.response().get());
            if (!(responseJson instanceof com.alibaba.fastjson.JSONObject json)) {
                continue;
            }
            String type = json.getString("type");
            if ("tool_error".equals(type)) {
                // A rejected mutation applied nothing; let the model retry without burning budget.
                continue;
            }
            String name = functionResponse.name().orElse("");
            boolean mutation = DrawioCanvasToolNames.DRAWING_RESULT_TOOL_NAMES.contains(name)
                    || "drawio_done".equals(type)
                    || DrawioCanvasToolNames.PATCH_CELLS.equals(type);
            if (!mutation) {
                continue;
            }
            com.alibaba.fastjson.JSONObject analysis = json.getJSONObject("analysis");
            boolean clean = analysis == null
                    ? isFinishBrief(json.getString("repairBrief"))
                    : analysis.getBooleanValue("valid");
            outcome = clean ? MutationOutcome.CLEAN : MutationOutcome.NEEDS_REPAIR;
        }
        return outcome;
    }

    private boolean isFinishBrief(String repairBrief) {
        // Legacy/blank responses count as clean so a tiny patch never forces an extra model round.
        return repairBrief == null || repairBrief.startsWith("APPLIED. No blocking issues");
    }

    private void completeStream(ResponseBodyEmitter emitter,
                                AtomicBoolean manuallyCompleted,
                                AtomicReference<Disposable> disposableRef,
                                String sessionId,
                                AgentUsageTelemetryService.StepScope drawingStep,
                                AgentUsageTelemetryService.RunScope runScope,
                                AtomicBoolean streamTelemetryCompleted,
                                BoundedTextCapture streamOutputCapture) {
        if (!manuallyCompleted.compareAndSet(false, true)) {
            return;
        }
        clearSessionConfig(sessionId, runScope.getContext().runId());
        captureBufferedStreamOutput(runScope, streamOutputCapture);
        completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, null);
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
        } catch (Exception ignored) {
        }
        try {
            streamResponseWriter.sendDone(emitter);
        } catch (Exception ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }

        Disposable disposable = disposableRef.get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private void completeStreamTelemetry(AtomicBoolean completed,
                                         AgentUsageTelemetryService.StepScope drawingStep,
                                         AgentUsageTelemetryService.RunScope runScope,
                                         Throwable error) {
        // Stream callbacks can race; telemetry should close each run exactly once.
        if (completed != null && !completed.compareAndSet(false, true)) {
            return;
        }
        recordStreamDone(runScope, error);
        captureStepPayload(drawingStep,
                error == null ? DebugTracePayloadKind.OUTPUT : DebugTracePayloadKind.ERROR,
                Map.of(
                        "status", error == null ? "SUCCESS" : "FAILED",
                        "errorClass", error == null ? "" : error.getClass().getSimpleName()));
        telemetryService().completeStep(drawingStep, error);
        telemetryService().completeRun(runScope, error);
    }

    private void handleStreamComplete(ResponseBodyEmitter emitter,
                                      ConcurrentHashMap<String, StringBuilder> authorBuffers,
                                      AtomicBoolean manuallyCompleted,
                                      String sessionId,
                                      AgentUsageTelemetryService.RunScope runScope) {
        if (manuallyCompleted.get()) {
            return;
        }
        clearSessionConfig(sessionId, runScope.getContext().runId());
        flushAuthorBuffers(emitter, authorBuffers);
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
            streamResponseWriter.sendDone(emitter);
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    private void clearSessionConfig(String sessionId, String runId) {
        if (!DrawioToolAccessContext.closeSession(sessionId, runId)) return;
        CustomApiConfigManager.clearConfig(sessionId);
        DrawioSkillAccessContext.clearSession(sessionId);
    }

    // Emit any buffered author output (non-XML lines such as patch_cells are only complete at flush time).
    private void flushAuthorBuffers(ResponseBodyEmitter emitter, ConcurrentHashMap<String, StringBuilder> authorBuffers) {
        for (StringBuilder buf : authorBuffers.values()) {
            String remaining = buf.toString().trim();
            buf.setLength(0);
            if (!remaining.isEmpty()) {
                try {
                    streamResponseWriter.processAndSendLine(emitter, "done", remaining);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void disposeStream(String sessionId, String eventName, Disposable disposable) {
        log.info("流式对话 {} sessionId:{}", eventName, sessionId);
        if (!disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private String logValue(String value) {
        if (null == value) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "...";
    }

}
