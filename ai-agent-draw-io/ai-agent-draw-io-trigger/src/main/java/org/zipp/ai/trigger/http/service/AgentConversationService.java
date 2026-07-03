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
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext;
import org.zipp.ai.domain.agent.service.ICanvasReviewService;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AgentConversationService {

    private static final int DEFAULT_MAX_REVIEW_ITERATIONS = 1;
    private static final int MAX_REVIEW_ITERATIONS_LIMIT = 3;

    @Resource
    private IChatService chatService;

    @Resource
    private IIntentRoutingService intentRoutingService;

    @Resource
    private ICanvasReviewService canvasReviewService;

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

    public ChatResponseDTO chat(ChatRequestDTO requestDTO) {
        AgentUsageTelemetryService.RunScope runScope = telemetryService().startRun(
                requestDTO.getUserId(), requestDTO.getAgentId(), requestDTO.getSessionId(), "chat",
                credentialSource(requestDTO), requestDTO.getModelCredentialId(), "openai", "unknown");
        AgentUsageTelemetryContext.Scope initialScope = AgentUsageTelemetryContext.bind(runScope.getContext());
        AgentUsageTelemetryContext.Scope configuredScope = null;
        Throwable runError = null;
        String sessionId = null;
        try {
            captureDebugTrace(runScope, "CHAT_REQUEST", requestDTO.getMessage());
            CustomApiConfigManager.CustomApiConfig config = buildCustomApiConfig(requestDTO);
            runScope = telemetryService().withProviderModel(runScope, config.getProvider(), config.getModel());
            configuredScope = AgentUsageTelemetryContext.bind(runScope.getContext());
            consumeAnonymousDemoQuota(requestDTO, config);
            consumeVerifiedUserPlatformQuota(requestDTO, config);
            sessionId = ensureSession(requestDTO);
            telemetryService().bindSession(sessionId, runScope);
            CustomApiConfigManager.setConfig(sessionId, config);
            requestDTO = requestWithStoredCanvas(requestDTO);
            final ChatRequestDTO currentRequest = requestDTO;
            IntentRoutingResult routingResult = telemetryService().recordStep(
                    "routing", () -> routeIntent(currentRequest, config));
            if (routingResult.isDirectReply()) {
                ChatResponseDTO responseDTO = new ChatResponseDTO();
                responseDTO.setType("user");
                responseDTO.setContent(telemetryService().recordStep(
                        "direct_answer", () -> resolveDirectAnswer(currentRequest, config, routingResult)));
                captureDebugTrace(runScope, "CHAT_RESPONSE", responseDTO.getContent());
                return responseDTO;
            }

            CanvasReviewContext reviewContext = routingResult.needsCanvasReview()
                    ? telemetryService().recordStep("review", () -> buildReviewContextIfNeeded(currentRequest, config, routingResult))
                    : null;
            int maxReviewIterations = effectiveMaxReviewIterations(currentRequest, routingResult);
            String routedMessage = buildRoutedMessage(currentRequest, routingResult, reviewContext, maxReviewIterations, currentRequest.getUserId(), currentRequest.getSkills());
            captureDebugTrace(runScope, "ROUTED_MESSAGE", routedMessage);
            final String finalSessionId = sessionId;
            ChatResponseDTO response = telemetryService().recordStep("drawing", () -> {
                List<String> messages = chatService.handleMessage(
                        currentRequest.getAgentId(), currentRequest.getUserId(), finalSessionId, routedMessage);
                return parseChatResponse(messages);
            });
            captureDebugTrace(runScope, "CHAT_RESPONSE", response.getContent());
            return response;
        } catch (Exception e) {
            runError = e;
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e);
        } finally {
            telemetryService().completeRun(runScope, runError);
            CustomApiConfigManager.clearConfig(sessionId);
            AgentUsageTelemetryContext.clearSession(sessionId);
            if (configuredScope != null) {
                configuredScope.close();
            }
            initialScope.close();
        }
    }

    public void stream(ChatRequestDTO requestDTO, ResponseBodyEmitter emitter) {
        AgentUsageTelemetryService.RunScope runScope = telemetryService().startRun(
                requestDTO.getUserId(), requestDTO.getAgentId(), requestDTO.getSessionId(), "chat_stream",
                credentialSource(requestDTO), requestDTO.getModelCredentialId(), "openai", "unknown");
        AgentUsageTelemetryContext.Scope initialScope = AgentUsageTelemetryContext.bind(runScope.getContext());
        AgentUsageTelemetryContext.Scope configuredScope = null;
        AgentUsageTelemetryService.StepScope drawingStep = null;
        AtomicBoolean streamTelemetryCompleted = new AtomicBoolean(false);
        String sessionId = null;
        try {
            captureDebugTrace(runScope, "CHAT_REQUEST", requestDTO.getMessage());
            CustomApiConfigManager.CustomApiConfig config = buildCustomApiConfig(requestDTO);
            runScope = telemetryService().withProviderModel(runScope, config.getProvider(), config.getModel());
            configuredScope = AgentUsageTelemetryContext.bind(runScope.getContext());
            consumeAnonymousDemoQuota(requestDTO, config);
            consumeVerifiedUserPlatformQuota(requestDTO, config);
            sessionId = ensureSession(requestDTO);
            final String finalSessionId = sessionId;
            telemetryService().bindSession(finalSessionId, runScope);
            CustomApiConfigManager.setConfig(finalSessionId, config);

            requestDTO = requestWithStoredCanvas(requestDTO);
            final ChatRequestDTO currentRequest = requestDTO;
            IntentRoutingResult routingResult = telemetryService().recordStep(
                    "routing", () -> routeIntent(currentRequest, config));
            if (routingResult.isDirectReply()) {
                try {
                    String answer = telemetryService().recordStep(
                            "direct_answer", () -> resolveDirectAnswer(currentRequest, config, routingResult));
                    streamResponseWriter.sendDirectReply(emitter, answer);
                    completeStreamTelemetry(streamTelemetryCompleted, null, runScope, null);
                } finally {
                    clearSessionConfig(finalSessionId);
                    AgentUsageTelemetryContext.clearSession(finalSessionId);
                }
                return;
            }

            // Each author has its own buffer because the ADK stream can interleave partial chunks.
            final ConcurrentHashMap<String, StringBuilder> authorBuffers = new ConcurrentHashMap<>();
            // Drawing-loop budget: one first draw plus N self-repair mutations (frontend Max Loops).
            final int maxRepairRounds = effectiveMaxReviewIterations(requestDTO, routingResult);
            final AtomicInteger mutationRounds = new AtomicInteger(0);
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final AtomicBoolean manuallyCompleted = new AtomicBoolean(false);
            final AtomicBoolean finalStreamTelemetryCompleted = streamTelemetryCompleted;
            final CanvasReviewContext reviewContext = routingResult.needsCanvasReview()
                    ? telemetryService().recordStep("review", () -> buildReviewContextIfNeeded(currentRequest, config, routingResult))
                    : null;
            final String routedMessage = buildRoutedMessage(currentRequest, routingResult, reviewContext, maxRepairRounds, currentRequest.getUserId(), currentRequest.getSkills());
            captureDebugTrace(runScope, "ROUTED_MESSAGE", routedMessage);
            // The current canvas travels in the request; keep it so patch_cells can merge a delta
            // without the model re-emitting the whole diagram.
            final String currentCanvasXml = contextBuilder().resolveCanvasXml(currentRequest);
            streamResponseWriter.setCurrentCanvas(emitter, currentCanvasXml);
            streamResponseWriter.setCanvasStateContext(emitter, currentRequest.getUserId(), currentRequest.getDiagramId(), currentRequest.getExpectedVersion());
            drawingStep = telemetryService().startStep("drawing");
            AgentUsageTelemetryContext.bindSession(finalSessionId, runScope.getContext().withPhase("drawing"));
            final AgentUsageTelemetryService.RunScope finalRunScope = runScope;
            final AgentUsageTelemetryService.StepScope finalDrawingStep = drawingStep;

            Disposable disposable = chatService.handleMessageStream(currentRequest.getAgentId(), currentRequest.getUserId(), finalSessionId, routedMessage)
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
                                                boolean budgetSpent = rounds >= maxRepairRounds + 1;
                                                if (outcome == MutationOutcome.CLEAN || budgetSpent || maxRepairRounds == 0) {
                                                    flushAuthorBuffers(emitter, authorBuffers);
                                                    completeStream(emitter, manuallyCompleted, disposableRef, finalSessionId,
                                                            finalDrawingStep, finalRunScope, finalStreamTelemetryCompleted);
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
                                            disposableRef, finalSessionId, finalDrawingStep, finalRunScope, finalStreamTelemetryCompleted);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            error -> {
                                clearSessionConfig(finalSessionId);
                                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, error);
                                AgentUsageTelemetryContext.clearSession(finalSessionId);
                                streamResponseWriter.handleStreamError(emitter, manuallyCompleted.get(), error);
                            },
                            () -> {
                                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, null);
                                handleStreamComplete(emitter, authorBuffers, manuallyCompleted, finalSessionId);
                            }
                    );
            disposableRef.set(disposable);
            if (manuallyCompleted.get() && !disposable.isDisposed()) {
                disposable.dispose();
            }

            emitter.onCompletion(() -> {
                clearSessionConfig(finalSessionId);
                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, null);
                AgentUsageTelemetryContext.clearSession(finalSessionId);
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onCompletion", disposable);
            });
            emitter.onTimeout(() -> {
                clearSessionConfig(finalSessionId);
                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope,
                        new IllegalStateException("stream_timeout"));
                AgentUsageTelemetryContext.clearSession(finalSessionId);
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onTimeout", disposable);
            });
            emitter.onError(e -> {
                clearSessionConfig(finalSessionId);
                completeStreamTelemetry(finalStreamTelemetryCompleted, finalDrawingStep, finalRunScope, e);
                AgentUsageTelemetryContext.clearSession(finalSessionId);
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onError", disposable);
            });
        } catch (AnonymousDemoQuotaExceededException e) {
            clearSessionConfig(sessionId);
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            AgentUsageTelemetryContext.clearSession(sessionId);
            log.info("Anonymous demo quota exhausted for userId:{}", SecretLogSanitizer.maskCapability(requestDTO.getUserId()));
            try {
                streamResponseWriter.sendTypedError(emitter, e.getCode(), e.getInfo());
                streamResponseWriter.sendDone(emitter);
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (PlatformDailyQuotaExceededException e) {
            clearSessionConfig(sessionId);
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            AgentUsageTelemetryContext.clearSession(sessionId);
            log.info("Verified user daily platform quota exhausted for userId:{}", SecretLogSanitizer.maskCapability(requestDTO.getUserId()));
            try {
                streamResponseWriter.sendTypedError(emitter, e.getCode(), e.getInfo());
                streamResponseWriter.sendDone(emitter);
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (AppException e) {
            clearSessionConfig(sessionId);
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            AgentUsageTelemetryContext.clearSession(sessionId);
            log.info("Stream request rejected for userId:{} code:{}",
                    SecretLogSanitizer.maskCapability(requestDTO.getUserId()), e.getCode());
            try {
                streamResponseWriter.sendTypedError(emitter, e.getCode(), e.getInfo());
                streamResponseWriter.sendDone(emitter);
            } catch (Exception ignored) {
            }
            emitter.complete();
        } catch (Exception e) {
            clearSessionConfig(sessionId);
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, e);
            AgentUsageTelemetryContext.clearSession(sessionId);
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

    private int effectiveMaxReviewIterations(ChatRequestDTO requestDTO, IntentRoutingResult routingResult) {
        // Localized edits skip the review/revision loop entirely: with 0 iterations the stream
        // completes as soon as the edited canvas is flushed, instead of waiting on review rounds.
        if (routingResult != null
                && "edit_existing".equals(routingResult.getTaskType())
                && !routingResult.needsCanvasReview()) {
            return 0;
        }
        return normalizeMaxReviewIterations(requestDTO.getMaxReviewIterations());
    }

    private int normalizeMaxReviewIterations(Integer maxReviewIterations) {
        if (maxReviewIterations == null) {
            return DEFAULT_MAX_REVIEW_ITERATIONS;
        }
        // Frontend owns the setting; backend clamps it to avoid runaway review loops.
        return Math.max(0, Math.min(maxReviewIterations, MAX_REVIEW_ITERATIONS_LIMIT));
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

    private String resolveDirectAnswer(ChatRequestDTO requestDTO, CustomApiConfigManager.CustomApiConfig config, IntentRoutingResult routingResult) {
        if (!routingResult.needsCanvasReview()) {
            return routingResult.getAnswer();
        }
        return canvasReviewService.answer(buildCanvasReviewCommand(requestDTO, config, routingResult));
    }

    private CanvasReviewContext buildReviewContextIfNeeded(ChatRequestDTO requestDTO,
                                                           CustomApiConfigManager.CustomApiConfig config,
                                                           IntentRoutingResult routingResult) {
        if (!routingResult.needsCanvasReview()) {
            return null;
        }
        return canvasReviewService.buildReviewContext(buildCanvasReviewCommand(requestDTO, config, routingResult));
    }

    private CanvasReviewCommand buildCanvasReviewCommand(ChatRequestDTO requestDTO,
                                                         CustomApiConfigManager.CustomApiConfig config,
                                                         IntentRoutingResult routingResult) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        return CanvasReviewCommand.builder()
                .userId(requestDTO.getUserId())
                .message(contextBuilder().buildReviewContextMessage(requestDTO, routingResult))
                .routingResult(routingResult)
                .customApiConfig(config)
                .build();
    }

    // Inject skill rules only when the drawer needs diagram-specific semantics; small edits stay lean.
    // User-specified skills (if any) override the router's automatic selection.
    private String skillSectionFor(IntentRoutingResult routingResult, String ownerId, List<String> userSkills) {
        String taskType = StringUtils.defaultString(routingResult.getTaskType());
        boolean generativeDraw = "create_new".equals(taskType)
                || "optimize_layout".equals(taskType)
                || ("edit_existing".equals(taskType) && routingResult.needsCanvasReview());
        if (!generativeDraw && (userSkills == null || userSkills.isEmpty())) {
            return "";
        }
        List<String> chosen = (userSkills != null && !userSkills.isEmpty())
                ? userSkills
                : List.of(StringUtils.defaultString(routingResult.getSkillName()));
        String section = skillContentProvider.buildSkillSection(chosen, ownerId);
        log.info("[skill-inject] taskType={} chosenSkills={} userSpecified={} ownerId={} injectedChars={}",
                taskType, chosen, userSkills != null && !userSkills.isEmpty(), ownerId, section.length());
        return section;
    }

    private String buildRoutedMessage(ChatRequestDTO requestDTO,
                                      IntentRoutingResult routingResult,
                                      CanvasReviewContext reviewContext,
                                      int maxReviewIterations,
                                      String ownerId,
                                      List<String> userSkills) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        com.alibaba.fastjson.JSONObject routingJson = new com.alibaba.fastjson.JSONObject();
        routingJson.put("intent", routingResult.getIntent());
        routingJson.put("drawMode", routingResult.getDrawMode());
        routingJson.put("diagramType", routingResult.getDiagramType());
        routingJson.put("skillName", routingResult.getSkillName());
        routingJson.put("taskType", routingResult.getTaskType());
        routingJson.put("needsCanvasQuality", routingResult.getNeedsCanvasQuality());
        routingJson.put("needsSemanticReview", routingResult.getNeedsSemanticReview());
        routingJson.put("answerMode", routingResult.getAnswerMode());
        routingJson.put("reason", routingResult.getReason());
        routingJson.put("maxRepairRounds", maxReviewIterations);
        List<String> allowedTools = allowedToolsFor(routingResult);
        routingJson.put("allowedTools", allowedTools);
        routingJson.put("toolPolicy", "Use only allowedTools for the initial draft. Self-repair rounds use modify_diagram or optimize_diagram(mode=route_only) and must not call create_diagram; explicit user redraws route through a new create_diagram action.");
        // Log derived routing controls only; the routed message below can contain full canvas XML.
        log.info("[draw-route] userId={} intent={} drawMode={} taskType={} allowedTools={} maxRepairRounds={} canvasReview={} semanticReview={} skillName={} reviewContext={}",
                SecretLogSanitizer.maskCapability(ownerId),
                logValue(routingResult.getIntent()),
                logValue(routingResult.getDrawMode()),
                logValue(routingResult.getTaskType()),
                allowedTools,
                maxReviewIterations,
                routingResult.getNeedsCanvasQuality(),
                routingResult.getNeedsSemanticReview(),
                logValue(routingResult.getSkillName()),
                null != reviewContext);

        String routedMessage = "[Intent Routing Result]\n"
                + routingJson.toJSONString()
                + "\n\n"
                + skillSectionFor(routingResult, ownerId, userSkills)
                + contextBuilder().buildDrawingContextMessage(requestDTO, routingResult);
        if (null == reviewContext) {
            return routedMessage;
        }

        return routedMessage
                + "\n\n"
                + reviewContext.getSerializedContext();
    }

    private List<String> allowedToolsFor(IntentRoutingResult routingResult) {
        String taskType = StringUtils.defaultString(routingResult.getTaskType());
        return switch (taskType) {
            case "create_new" -> List.of(DrawioCanvasToolNames.CREATE_DIAGRAM);
            case "edit_existing" -> List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM);
            case "optimize_layout" -> List.of(DrawioCanvasToolNames.OPTIMIZE_DIAGRAM);
            case "review_only", "none" -> List.of();
            default -> DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES;
        };
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
                                    AtomicBoolean streamTelemetryCompleted) throws Exception {
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
                            drawingStep, runScope, streamTelemetryCompleted);
                    return;
                }
            }
        }

        if (!isPartial) {
            String remaining = buffer.toString().trim();
            buffer.setLength(0);
            if (!remaining.isEmpty() && streamResponseWriter.processAndSendLine(emitter, phase, remaining)) {
                completeStream(emitter, manuallyCompleted, disposableRef, sessionId,
                        drawingStep, runScope, streamTelemetryCompleted);
            }
        }
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
                                AtomicBoolean streamTelemetryCompleted) {
        if (!manuallyCompleted.compareAndSet(false, true)) {
            return;
        }
        clearSessionConfig(sessionId);
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
        telemetryService().completeStep(drawingStep, error);
        telemetryService().completeRun(runScope, error);
    }

    private void handleStreamComplete(ResponseBodyEmitter emitter,
                                      ConcurrentHashMap<String, StringBuilder> authorBuffers,
                                      AtomicBoolean manuallyCompleted,
                                      String sessionId) {
        if (manuallyCompleted.get()) {
            return;
        }
        clearSessionConfig(sessionId);
        flushAuthorBuffers(emitter, authorBuffers);
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
            streamResponseWriter.sendDone(emitter);
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    private void clearSessionConfig(String sessionId) {
        CustomApiConfigManager.clearConfig(sessionId);
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
