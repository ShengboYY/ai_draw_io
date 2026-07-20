package org.zipp.ai.trigger.http.service;

import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.api.dto.ChatResponseDTO;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSecret;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaExceededException;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.IModelCredentialService;
import org.zipp.ai.domain.account.service.PlatformDailyQuotaExceededException;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationPurpose;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingProbe;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.model.valobj.visualreview.DrawerContinuationContext;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
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
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerCommand;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerResult;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerService;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.grounding.EvidencePromptAssembler;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;
import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;
import org.zipp.ai.types.util.SecretLogSanitizer;
import com.alibaba.fastjson.JSON;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

    private static final int DEFAULT_MAX_DETERMINISTIC_REPAIR_ROUNDS = 0;
    private static final int MAX_DETERMINISTIC_REPAIR_ROUNDS = 3;
    private static final int MAX_VISUAL_CONTINUATION_DETERMINISTIC_REPAIR_ROUNDS = 0;
    private static final int MAX_BUFFERED_STREAM_CAPTURE_CHARS = 64_000;
    private static final String DRAWER_CONTINUATION_REASON = "production_visual_review_continuation";

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

    @Autowired(required = false)
    private RequestProbeService requestProbeService;

    @Autowired(required = false)
    private EvidencePreparationModule evidencePreparationModule;

    @Autowired(required = false)
    private EvidencePromptAssembler evidencePromptAssembler;

    @Autowired(required = false)
    private GroundedRunControlPort groundedRunControlPort;

    @Autowired(required = false)
    private ConfiguredModelClaimSupportVerifier claimSupportVerifier;

    @Autowired(required = false)
    private ConfiguredModelEvidenceAnswerGenerator evidenceAnswerGenerator;

    @Autowired(required = false)
    private EvidenceAnswerService evidenceAnswerService;

    @Autowired(required = false)
    private IDiagramConversationStore diagramConversationStore;

    @Value("${app.material-rag.enabled:false}")
    private boolean materialRagEnabled;

    private final CanvasVisualReviewPolicy canvasVisualReviewPolicy = new CanvasVisualReviewPolicy();

    public ChatResponseDTO chat(ChatRequestDTO requestDTO) {
        RunResourceDomain evidenceResources = new RunResourceDomain();
        AtomicReference<PreparedEvidence> preparedEvidenceRef = new AtomicReference<>();
        AtomicReference<GroundedRunControlPort.RunIdentity> groundedRunRef = new AtomicReference<>();
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
            if (claimSupportVerifier != null) {
                claimSupportVerifier.register(runScope.getContext().runId(), requestDTO.getUserId(), config);
            }
            if (evidenceAnswerGenerator != null) {
                evidenceAnswerGenerator.register(runScope.getContext().runId(), requestDTO.getUserId(), config);
            }
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
            RequestProbe requestProbe = probeRequest(currentRequest);
            IntentRoutingResult routingResult = recordCapturedStep(
                    "routing", requestStepInput(currentRequest), AgentConversationService::routingStepOutput,
                    () -> routeIntent(currentRequest, config, requestProbe));
            recordRoutingDecision(runScope, routingResult);
            if (routingResult.isEvidenceAnswer() && shouldPrepareEvidence(currentRequest, routingResult)
                    && groundedRunControlPort != null) {
                GroundedRunControlPort.RunIdentity identity = new GroundedRunControlPort.RunIdentity(
                        currentRequest.getUserId(),
                        StringUtils.defaultIfBlank(currentRequest.getRequestId(), runScope.getContext().requestId()),
                        runScope.getContext().runId());
                groundedRunControlPort.start(identity);
                groundedRunRef.set(identity);
            }
            ChatResponseDTO evidenceResponse = prepareEvidenceResponse(
                    currentRequest, routingResult, requestProbe, evidenceResources,
                    EvidenceProgressListener.NOOP, CancellationSignal.NEVER,
                    routingResult.isEvidenceAnswer() ? preparedEvidenceRef : null);
            if (evidenceResponse != null) {
                attachCorrelation(evidenceResponse, runScope);
                captureRunOutput(runScope, evidenceResponse, currentRequest.getDiagramId());
                return evidenceResponse;
            }
            if (routingResult.isEvidenceAnswer()) {
                PreparedEvidence prepared = preparedEvidenceRef.get();
                EvidenceAnswerResult answer = prepared == null || evidenceAnswerService == null
                        ? EvidenceAnswerResult.rejected(responseMessageId(currentRequest),
                        List.of("EVIDENCE_ANSWER_UNAVAILABLE"))
                        : evidenceAnswerService.answer(evidenceAnswerCommand(
                                currentRequest, requestProbe, runScope.getContext().requestId()), prepared);
                ChatResponseDTO responseDTO = evidenceResponse(answer.committed()
                                ? "evidence_answer" : "grounding_rejected",
                        answer.committed() ? answer.content()
                                : "可用资料不足以生成经过验证的回答。 / The prepared sources did not support a verified answer.");
                attachCorrelation(responseDTO, runScope);
                captureRunOutput(runScope, responseDTO, currentRequest.getDiagramId());
                return responseDTO;
            }
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
            RoutedDrawMessage routedMessage = buildRoutedDrawMessage(
                    currentRequest, routingResult, maxDeterministicRepairRounds,
                    currentRequest.getUserId(), currentRequest.getSkills(), false);
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
            evidenceResources.closeExactlyOnce(runError == null ? CloseReason.COMPLETED : CloseReason.FAILED);
            cancelGroundedRun(groundedRunRef.get());
            unregisterClaimVerifier(runScope.getContext().runId());
            unregisterAnswerGenerator(runScope.getContext().runId());
            telemetryService().completeRun(runScope, runError);
            clearSessionConfig(sessionId, runScope.getContext().runId());
            if (configuredScope != null) {
                configuredScope.close();
            }
            initialScope.close();
        }
    }

    public void stream(ChatRequestDTO requestDTO, ResponseBodyEmitter emitter) {
        stream(requestDTO, emitter, null, "chat_stream", null);
    }

    public void continueDrawing(ChatRequestDTO requestDTO,
                                DrawerContinuationContext continuation,
                                ResponseBodyEmitter emitter) {
        log.info("[drawer-continuation] event=start runId={} sourceRunId={} parentRunId={} repairRound={} diagramId={} expectedVersion={} expectedHash={}",
                logValue(requestDTO.getRunId()), logValue(requestDTO.getSourceRunId()),
                logValue(requestDTO.getParentRunId()), requestDTO.getVisualRepairRound(),
                logValue(requestDTO.getDiagramId()), requestDTO.getExpectedVersion(),
                logValue(requestDTO.getExpectedContentHash()));
        // Visual review is feedback on an already-routed task. Continue the same Drawer loop without
        // asking the intent model to reinterpret the server-authored feedback as a new user request.
        IntentRoutingResult continuationRoute = new IntentRoutingResult();
        // Keep the established edit route for prompt context; the continuation policy exposes both
        // local repair tools so the Drawer can select the smallest operation from the review evidence.
        continuationRoute.setRouteType("edit_existing");
        continuationRoute.setDiagramType(continuation.diagramType());
        continuationRoute.setSkillName("none");
        continuationRoute.setReason(DRAWER_CONTINUATION_REASON);
        stream(requestDTO, emitter, continuationRoute, "drawer_continuation_stream",
                new CanvasMutationIntent(CanvasMutationPurpose.VLM_REPAIR, continuation.authorization()));
    }

    private void stream(ChatRequestDTO requestDTO,
                        ResponseBodyEmitter emitter,
                        IntentRoutingResult forcedRoutingResult,
                        String operation,
                        CanvasMutationIntent mutationIntent) {
        AgentUsageTelemetryService.RunScope runScope = telemetryService().startRun(
                requestDTO.getRunId(), requestDTO.getRequestId(),
                requestDTO.getUserId(), requestDTO.getAgentId(), requestDTO.getSessionId(), operation,
                requestDTO.getDiagramId(), credentialSource(requestDTO), requestDTO.getModelCredentialId(), "openai", "unknown");
        RunResourceDomain evidenceResources = new RunResourceDomain();
        AtomicReference<PreparedEvidence> preparedEvidenceRef = new AtomicReference<>();
        AtomicReference<GroundedRunControlPort.RunIdentity> groundedRunRef = new AtomicReference<>();
        String groundedRunId = runScope.getContext().runId();
        AtomicBoolean evidenceCancelled = new AtomicBoolean(false);
        // Register cleanup before probe/retrieval so disconnects cannot strand leases or in-flight work.
        emitter.onCompletion(() -> {
            evidenceResources.closeExactlyOnce(CloseReason.COMPLETED);
            cancelGroundedRun(groundedRunRef.get());
            unregisterClaimVerifier(groundedRunId);
            unregisterAnswerGenerator(groundedRunId);
        });
        emitter.onTimeout(() -> {
            evidenceCancelled.set(true);
            evidenceResources.closeExactlyOnce(CloseReason.TIMED_OUT);
            cancelGroundedRun(groundedRunRef.get());
            unregisterClaimVerifier(groundedRunId);
            unregisterAnswerGenerator(groundedRunId);
        });
        emitter.onError(error -> {
            evidenceCancelled.set(true);
            evidenceResources.closeExactlyOnce(CloseReason.CLIENT_DISCONNECTED);
            cancelGroundedRun(groundedRunRef.get());
            unregisterClaimVerifier(groundedRunId);
            unregisterAnswerGenerator(groundedRunId);
        });
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
            if (claimSupportVerifier != null) {
                claimSupportVerifier.register(groundedRunId, requestDTO.getUserId(), config);
            }
            if (evidenceAnswerGenerator != null) {
                evidenceAnswerGenerator.register(groundedRunId, requestDTO.getUserId(), config);
            }
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
            RequestProbe requestProbe = probeRequest(currentRequest);
            IntentRoutingResult routingResult = forcedRoutingResult == null
                    ? recordCapturedStep(
                    "routing", requestStepInput(currentRequest), AgentConversationService::routingStepOutput,
                    () -> routeIntent(currentRequest, config, requestProbe))
                    : forcedRoutingResult;
            recordRoutingDecision(runScope, routingResult);
            // The UI uses this compact event to describe the selected route without exposing model reasoning.
            streamResponseWriter.sendRoute(emitter, routingResult.getRouteType(),
                    routingResult.getDiagramType(), routingResult.getSkillName());
            if (forcedRoutingResult == null) {
                if ((routingResult.isDrawAction() || routingResult.isEvidenceAnswer())
                        && shouldPrepareEvidence(currentRequest, routingResult)
                        && groundedRunControlPort != null) {
                    GroundedRunControlPort.RunIdentity identity = new GroundedRunControlPort.RunIdentity(
                            currentRequest.getUserId(),
                            StringUtils.defaultIfBlank(currentRequest.getRequestId(), runScope.getContext().requestId()),
                            runScope.getContext().runId());
                    groundedRunControlPort.start(identity);
                    groundedRunRef.set(identity);
                }
                ChatResponseDTO evidenceResponse = prepareEvidenceResponse(currentRequest, routingResult,
                        requestProbe, evidenceResources, (stage, completed, total) -> {
                            try {
                                streamResponseWriter.sendEvidenceProgress(emitter, stage, completed, total);
                            } catch (Exception error) {
                                evidenceCancelled.set(true);
                            }
                        }, evidenceCancelled::get, preparedEvidenceRef);
                if (evidenceResponse != null) {
                    captureRunOutput(runScope, evidenceResponse, currentRequest.getDiagramId());
                    if ("target_clarification".equals(evidenceResponse.getType())) {
                        streamResponseWriter.sendTargetClarification(emitter, evidenceResponse);
                    } else {
                        streamResponseWriter.sendEvidenceOutcome(emitter,
                                evidenceStreamEvent(evidenceResponse.getType()), evidenceResponse.getContent());
                    }
                    completeStreamTelemetry(streamTelemetryCompleted, null, runScope, null);
                    return;
                }
            }
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
            if (routingResult.isEvidenceAnswer()) {
                PreparedEvidence prepared = preparedEvidenceRef.get();
                if (prepared == null || evidenceAnswerService == null) {
                    streamResponseWriter.sendEvidenceOutcome(emitter, "degraded",
                            "证据回答当前不可用。 / Grounded answers are currently unavailable.");
                    completeStreamTelemetry(streamTelemetryCompleted, null, runScope, null);
                    return;
                }
                EvidenceAnswerResult answer = evidenceAnswerService.answer(
                        evidenceAnswerCommand(currentRequest, requestProbe, runScope.getContext().requestId()), prepared);
                if (!answer.committed()) {
                    streamResponseWriter.sendEvidenceOutcome(emitter, "grounding_rejected",
                            "可用资料不足以生成经过验证的回答。 / The prepared sources did not support a verified answer.");
                } else {
                    captureRunOutput(runScope, "evidence_answer", answer.content(), currentRequest.getDiagramId());
                    streamResponseWriter.sendEvidenceAnswer(emitter, answer);
                }
                completeStreamTelemetry(streamTelemetryCompleted, null, runScope, null);
                return;
            }
            // Canvas mutations need a persistent identity; otherwise the final candidate would have
            // no optimistic-lock baseline and could bypass the mutation acceptance seam.
            if (StringUtils.isBlank(currentRequest.getDiagramId())) {
                throw new AppException(
                        ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "diagramId is required for canvas mutations");
            }

            // Each author has its own buffer because the ADK stream can interleave partial chunks.
            final ConcurrentHashMap<String, StringBuilder> authorBuffers = new ConcurrentHashMap<>();
            // Drawing-loop budget: one requested mutation plus N optional hard-structure repairs.
            // A VLM continuation always uses zero so its candidate is reviewed immediately.
            final int maxRepairRounds = forcedRoutingResult == null
                    ? effectiveDeterministicRepairRounds(requestDTO, routingResult)
                    : MAX_VISUAL_CONTINUATION_DETERMINISTIC_REPAIR_ROUNDS;
            final AtomicInteger mutationRounds = new AtomicInteger(0);
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final AtomicBoolean manuallyCompleted = new AtomicBoolean(false);
            final AtomicBoolean finalStreamTelemetryCompleted = streamTelemetryCompleted;
            final AtomicBoolean finalFirstStreamOutputRecorded = firstStreamOutputRecorded;
            final BoundedTextCapture finalStreamOutputCapture = streamOutputCapture;
            final long finalStreamStartedNanos = streamStartedNanos;
            // Mutation intent is constructed by this service, unlike model-authored routing fields.
            final boolean drawerContinuation = forcedRoutingResult != null
                    && mutationIntent != null
                    && mutationIntent.purpose() == CanvasMutationPurpose.VLM_REPAIR;
            PreparedEvidence preparedEvidence = preparedEvidenceRef.get();
            EvidenceAccessContext evidenceAccess = preparedEvidence == null
                    ? null : EvidenceAccessContext.from(preparedEvidence.bundle(), true);
            final RoutedDrawMessage routedMessage = buildRoutedDrawMessage(
                    currentRequest, routingResult, maxRepairRounds,
                    currentRequest.getUserId(), currentRequest.getSkills(), drawerContinuation, evidenceAccess);
            Object routedTracePayload = evidenceAccess == null
                    ? traceField("message", routedMessage.message())
                    : groundedPromptTrace(routedMessage.message(), evidenceAccess);
            captureDebugTrace(runScope, "ROUTED_MESSAGE", JSON.toJSONString(routedTracePayload));
            // The current canvas travels in the request; keep it so patch_cells can merge a delta
            // without the model re-emitting the whole diagram.
            final String currentCanvasXml = contextBuilder().resolveCanvasXml(currentRequest);
            streamResponseWriter.setCurrentCanvas(emitter, currentCanvasXml);
            drawingStep = telemetryService().startStep("drawing");
            captureStepPayload(drawingStep, DebugTracePayloadKind.INPUT, routedTracePayload);
            streamResponseWriter.setCanvasStateContext(
                    emitter,
                    currentRequest.getUserId(),
                    currentRequest.getDiagramId(),
                    currentRequest.getExpectedVersion(),
                    currentRequest.getExpectedContentHash(),
                    routingResult.getDiagramType(),
                    mutationIntent == null ? null : mutationIntent.purpose(),
                    mutationIntent == null ? null : mutationIntent.authorization(),
                    currentRequest.getVisualRepairRound(),
                    runScope.getContext().runId(),
                    drawingStep == null ? runScope.getContext().runId() : drawingStep.getStepContext().spanId());
            if (evidenceAccess != null) {
                streamResponseWriter.setEvidenceContext(
                        emitter, evidenceAccess, evidenceResources, isStrictEvidenceRequest(currentRequest, routingResult),
                        StringUtils.defaultIfBlank(currentRequest.getRequestId(), runScope.getContext().requestId()),
                        runScope.getContext().runId());
            }
            DrawioSkillAccessContext.bindSession(finalSessionId, routedMessage.allowedSkillNames());
            DrawioToolAccessContext.applyToolPolicy(
                    runScope.getContext().runId(), routedMessage.toolPolicy());
            final AgentUsageTelemetryService.RunScope finalRunScope = runScope;
            final AgentUsageTelemetryService.StepScope finalDrawingStep = drawingStep;

            Map<String, Object> initialAgentState = new LinkedHashMap<>();
            if (StringUtils.isNotBlank(currentCanvasXml)) {
                initialAgentState.put(DrawioMutationResultPostProcessor.DRAFT_DIAGRAM_STATE_KEY, currentCanvasXml);
            }
            initialAgentState.put(
                    DrawioMutationResultPostProcessor.DIAGRAM_TYPE_STATE_KEY,
                    StringUtils.defaultString(routingResult.getDiagramType()));
            Disposable disposable = chatService.handleMessageStream(
                            currentRequest.getAgentId(),
                            currentRequest.getUserId(),
                            finalSessionId,
                            routedMessage.message(),
                            finalDrawingStep != null
                                    ? finalDrawingStep.getStepContext()
                                    : runScope.getContext().withPhase("drawing"),
                            initialAgentState)
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
                                handleStreamComplete(
                                        emitter, authorBuffers, manuallyCompleted, finalSessionId, finalDrawingStep,
                                        finalRunScope, finalStreamTelemetryCompleted);
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
            if (evidenceResources.isClosed()) {
                unregisterClaimVerifier(groundedRunId);
                unregisterAnswerGenerator(groundedRunId);
            }
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
                if (StringUtils.isBlank(requestDTO.getExpectedContentHash())) {
                    requestDTO.setExpectedContentHash(state.getContentHash());
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
        if (requestDTO != null && StringUtils.isNotBlank(requestDTO.getSourceRunId())) {
            // Correlation fields contain no prompt/XML and make repair runs traceable to the original draw.
            metadata.put("sourceRunId", requestDTO.getSourceRunId());
            metadata.put("parentRunId", StringUtils.defaultString(requestDTO.getParentRunId()));
            metadata.put("visualRepairRound", requestDTO.getVisualRepairRound());
        }
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

    /** Evidence body is model-only; debug storage receives opaque diagnostics, never source text. */
    private Map<String, Object> groundedPromptTrace(String routedMessage, EvidenceAccessContext access) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("messageSha256", sha256(routedMessage));
        trace.put("evidenceCount", access.items().size());
        trace.put("citationKeys", access.allowedCitationKeys());
        trace.put("modalities", access.items().stream().map(EvidenceBundleItem::modality).distinct().toList());
        trace.put("supportRoles", access.items().stream().map(EvidenceBundleItem::supportRole).distinct().toList());
        return trace;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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

    private IntentRoutingResult routeIntent(ChatRequestDTO requestDTO,
                                            CustomApiConfigManager.CustomApiConfig config,
                                            RequestProbe requestProbe) {
        DrawioPromptContextBuilder contextBuilder = contextBuilder();
        return intentRoutingService.route(IntentRoutingCommand.builder()
                .userId(requestDTO.getUserId())
                .message(contextBuilder.buildIntentMessage(requestDTO))
                .requestProbe(intentProbe(requestProbe))
                .customApiConfig(config)
                .build());
    }

    private RequestProbe probeRequest(ChatRequestDTO requestDTO) {
        SourceMode mode = sourceMode(requestDTO == null ? null : requestDTO.getSourceMode());
        if (requestDTO == null) {
            return new RequestProbe(SourceProbe.empty(mode), new CanvasProbe(false, 0, 0,
                    null, "", 0, List.of(), false, false));
        }
        if (materialRagEnabled && requestProbeService != null) {
            try {
                return requestProbeService.probe(new RequestProbeCommand(owner(requestDTO),
                        requestDTO.getDiagramId(), requestDTO.getSessionId(), mode,
                        safeList(requestDTO.getSelectedVersionIds()), safeList(requestDTO.getSelectedCellIds()),
                        requestDTO.getSelectionCanvasVersion(), requestDTO.getSelectionContentHash()));
            } catch (RuntimeException exception) {
                log.warn("Request probe failed closed. diagramId={}",
                        SecretLogSanitizer.maskCapability(requestDTO.getDiagramId()), exception);
                return new RequestProbe(SourceProbe.empty(mode), CanvasProbe.unavailableProbe());
            }
        }
        // Legacy drawing still uses server state, but only the content-free existence bit reaches the router.
        if (canvasStateStore == null) {
            return new RequestProbe(SourceProbe.empty(mode), CanvasProbe.unavailableProbe());
        }
        if (StringUtils.isBlank(requestDTO.getDiagramId())) {
            return new RequestProbe(SourceProbe.empty(mode), CanvasProbe.unavailableProbe());
        }
        return canvasStateStore.find(requestDTO.getUserId(), requestDTO.getDiagramId())
                .map(state -> new RequestProbe(SourceProbe.empty(mode),
                        new CanvasProbe(hasDrawableCell(state.getCurrentXml()),
                                hasDrawableCell(state.getCurrentXml()) ? 1 : 0, 0, state.getVersion(),
                                StringUtils.defaultString(state.getContentHash()), 0, List.of(), false, false)))
                .orElseGet(() -> new RequestProbe(SourceProbe.empty(mode), CanvasProbe.unavailableProbe()));
    }

    private IntentRoutingProbe intentProbe(RequestProbe requestProbe) {
        SourceProbe source = requestProbe.sources();
        CanvasProbe canvas = requestProbe.canvas();
        return new IntentRoutingProbe(canvas.hasCanvas(), canvas.nodeCount(), canvas.edgeCount(),
                source.selectedCount(), source.pendingConversationUploadCount(),
                source.hasReadyDiagramSources() || source.hasReadyChartbookSources()
                        || source.hasReadyLibrarySources(),
                source.hasVisualEvidence(), canvas.selectionVersionMismatch(), source.effectiveSourceMode());
    }

    private ChatResponseDTO prepareEvidenceResponse(ChatRequestDTO requestDTO,
                                                    IntentRoutingResult routing,
                                                    RequestProbe requestProbe,
                                                    RunResourceDomain resources,
                                                    EvidenceProgressListener progress,
                                                    CancellationSignal cancellation,
                                                    AtomicReference<PreparedEvidence> preparedEvidenceRef) {
        if (!shouldPrepareEvidence(requestDTO, routing)) return null;
        boolean strict = isStrictEvidenceRequest(requestDTO, routing);
        if (!materialRagEnabled || evidencePreparationModule == null) {
            return strict ? evidenceResponse("capability_unavailable",
                    "资料检索功能当前不可用，画布未被修改。 / Evidence retrieval is currently unavailable; the canvas was not modified.")
                    : null;
        }
        EvidencePreparationCommand command = new EvidencePreparationCommand(owner(requestDTO),
                requestDTO.getDiagramId(), requestDTO.getSessionId(),
                StringUtils.defaultIfBlank(requestDTO.getRequestId(), requestDTO.getRunId()), requestDTO.getRunId(),
                requestDTO.getMessage(), requestProbe.canvas(),
                new ValidatedSelection(safeList(requestDTO.getSelectedCellIds()),
                        requestDTO.getSelectionCanvasVersion(), requestDTO.getSelectionContentHash()),
                sourceMode(requestDTO.getSourceMode()), safeList(requestDTO.getSelectedVersionIds()),
                StringUtils.defaultIfBlank(routing.getEvidenceNeed(), "OPTIONAL"),
                StringUtils.defaultIfBlank(routing.getTargetNeed(), "NONE"));
        PreparationOutcome outcome = evidencePreparationModule.prepare(command, resources, progress, cancellation)
                .toCompletableFuture().join();
        if (outcome instanceof PreparationOutcome.NotRequired) return null;
        if (!strict && (outcome instanceof PreparationOutcome.InsufficientEvidence
                || outcome instanceof PreparationOutcome.Failed)) {
            // Optional AUTO retrieval may degrade to the existing text-only Drawer path.
            return null;
        }
        if (outcome instanceof PreparationOutcome.Waiting) {
            return evidenceResponse("material_waiting",
                    "会话中的资料仍在处理中，完成后可继续本轮请求。 / Conversation material is still processing.");
        }
        if (outcome instanceof PreparationOutcome.MaterialNotReady) {
            return evidenceResponse("material_not_ready",
                    "所选资料尚未就绪，请稍后重试。 / The selected library material is not ready yet.");
        }
        if (outcome instanceof PreparationOutcome.TargetClarification) {
            PreparationOutcome.TargetClarification clarification =
                    (PreparationOutcome.TargetClarification) outcome;
            ChatResponseDTO response = evidenceResponse("target_clarification",
                    "无法唯一确定要处理的画布对象，请先明确选择节点或连线。 / Please select the intended canvas target.");
            response.setTargetCandidates(clarification.candidates().stream().map(candidate -> {
                ChatResponseDTO.TargetCandidateDTO value = new ChatResponseDTO.TargetCandidateDTO();
                value.setCellId(candidate.cellId());
                value.setKind(candidate.kind());
                value.setShortLabel(candidate.shortLabel());
                value.setReasonCode(candidate.reasonCode());
                return value;
            }).toList());
            response.setCanvasVersion(requestProbe.canvas().serverCanvasVersion());
            response.setContentHash(requestProbe.canvas().contentHash());
            return response;
        }
        if (outcome instanceof PreparationOutcome.CanvasChangedRetry) {
            return evidenceResponse("canvas_changed_retry",
                    "画布已发生变化，请刷新后重试。 / The canvas changed; refresh and retry.");
        }
        if (outcome instanceof PreparationOutcome.StaleCanvasSelection) {
            return evidenceResponse("stale_canvas_selection",
                    "画布选择已过期，请重新选择目标后重试。 / The canvas selection is stale; select the target again.");
        }
        if (outcome instanceof PreparationOutcome.CanvasUnavailable) {
            return evidenceResponse("canvas_unavailable",
                    "无法读取可信的服务端画布，已停止本轮操作。 / The trusted server canvas is unavailable.");
        }
        if (outcome instanceof PreparationOutcome.Cancelled) {
            return evidenceResponse("cancelled", "请求已取消。 / Request cancelled.");
        }
        if (outcome instanceof PreparationOutcome.Ready ready
                && preparedEvidenceRef != null && (routing.isDrawAction() || routing.isEvidenceAnswer())) {
            preparedEvidenceRef.set(ready.preparedEvidence());
            return null;
        }
        if (outcome instanceof PreparationOutcome.Ready) {
            // WP7 evidence answers and the synchronous mutation path remain closed until their own
            // atomic persistence seams are available.
            return evidenceResponse("capability_unavailable",
                    "资料证据已准备完成，但当前请求的证据化服务尚未启用，画布未被修改。 / Evidence is ready, but this grounded capability is not enabled yet.");
        }
        return evidenceResponse("insufficient_evidence",
                "当前资料不足以安全完成请求，画布未被修改。 / The available evidence is insufficient.");
    }

    private boolean isStrictEvidenceRequest(ChatRequestDTO requestDTO, IntentRoutingResult routing) {
        return routing.isEvidenceAnswer() || "REQUIRED".equals(routing.getEvidenceNeed())
                || sourceMode(requestDTO.getSourceMode()) == SourceMode.EXPLICIT_ONLY
                || !safeList(requestDTO.getSelectedVersionIds()).isEmpty();
    }

    private boolean shouldPrepareEvidence(ChatRequestDTO requestDTO, IntentRoutingResult routing) {
        if (routing == null || requestDTO == null) return false;
        if (routing.isEvidenceAnswer() || "REQUIRED".equals(routing.getEvidenceNeed())) return true;
        if (!safeList(requestDTO.getSelectedVersionIds()).isEmpty()) return true;
        SourceMode mode = sourceMode(requestDTO.getSourceMode());
        return materialRagEnabled && routing.isDrawAction() && !"NONE".equals(routing.getEvidenceNeed())
                && mode != SourceMode.NONE;
    }

    private ChatResponseDTO evidenceResponse(String type, String content) {
        ChatResponseDTO response = new ChatResponseDTO();
        response.setType(type);
        response.setContent(content);
        return response;
    }

    private String evidenceStreamEvent(String responseType) {
        return switch (StringUtils.defaultString(responseType)) {
            case "material_waiting" -> "source_wait_started";
            case "material_not_ready" -> "source_not_ready";
            case "target_clarification" -> "target_clarification";
            case "stale_canvas_selection" -> "stale_canvas_selection";
            default -> "degraded";
        };
    }

    private CatalogOwner owner(ChatRequestDTO requestDTO) {
        String ownerKey = StringUtils.defaultIfBlank(requestDTO.getUserId(), "unknown-owner");
        OwnerType type = ownerKey.startsWith("anon_") ? OwnerType.ANONYMOUS : OwnerType.USER;
        return new CatalogOwner(type, ownerKey);
    }

    private SourceMode sourceMode(String value) {
        if (StringUtils.isBlank(value)) return SourceMode.AUTO;
        try {
            return SourceMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SourceMode.AUTO;
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasDrawableCell(String xml) {
        return xml != null && (xml.contains("vertex=\"1\"") || xml.contains("vertex='1'")
                || xml.contains("edge=\"1\"") || xml.contains("edge='1'"));
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
        if (!visualReviewEnabled(requestDTO)) {
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

    private boolean visualReviewEnabled(ChatRequestDTO requestDTO) {
        // Plain unit tests construct the service outside Spring; preserve the pre-rollout behavior there.
        return visualReviewRolloutPolicy == null || visualReviewRolloutPolicy.isReviewEnabled(
                requestDTO == null ? null : requestDTO.getUserId(),
                requestDTO == null ? null : requestDTO.getDiagramId());
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
        return buildRoutedDrawMessage(
                requestDTO, routingResult, maxDeterministicRepairRounds, ownerId, userSkills, false);
    }

    private RoutedDrawMessage buildRoutedDrawMessage(ChatRequestDTO requestDTO,
                                                     IntentRoutingResult routingResult,
                                                     int maxDeterministicRepairRounds,
                                                     String ownerId,
                                                     List<String> userSkills,
                                                     boolean drawerContinuation) {
        return buildRoutedDrawMessage(requestDTO, routingResult, maxDeterministicRepairRounds,
                ownerId, userSkills, drawerContinuation, null);
    }

    private RoutedDrawMessage buildRoutedDrawMessage(ChatRequestDTO requestDTO,
                                                     IntentRoutingResult routingResult,
                                                     int maxDeterministicRepairRounds,
                                                     String ownerId,
                                                     List<String> userSkills,
                                                     boolean drawerContinuation,
                                                     EvidenceAccessContext evidenceAccess) {
        requestDTO = requestWithStoredCanvas(requestDTO);
        com.alibaba.fastjson.JSONObject routingJson = new com.alibaba.fastjson.JSONObject();
        routingJson.put("routeType", routingResult.getRouteType());
        routingJson.put("diagramType", routingResult.getDiagramType());
        routingJson.put("skillName", routingResult.getSkillName());
        routingJson.put("reason", routingResult.getReason());
        routingJson.put("maxRepairRounds", maxDeterministicRepairRounds);
        DrawioToolAccessContext.ToolPolicy toolPolicy = toolPolicyFor(routingResult, drawerContinuation);
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
                + contextBuilder().buildDrawingContextMessage(requestDTO, routingResult)
                + (evidenceAccess == null ? "" : evidencePromptAssembler().assemble(evidenceAccess));
        return new RoutedDrawMessage(
                routedMessage,
                skillSection.requiredSkillNames(),
                toolPolicy);
    }

    private EvidencePromptAssembler evidencePromptAssembler() {
        return evidencePromptAssembler == null ? new EvidencePromptAssembler() : evidencePromptAssembler;
    }

    private void cancelGroundedRun(GroundedRunControlPort.RunIdentity identity) {
        if (identity == null || groundedRunControlPort == null) return;
        try {
            groundedRunControlPort.cancel(identity);
        } catch (RuntimeException error) {
            log.warn("Failed to cancel grounded run. runId={}", logValue(identity.runId()), error);
        }
    }

    private void unregisterClaimVerifier(String runId) {
        if (claimSupportVerifier != null) claimSupportVerifier.unregister(runId);
    }

    private void unregisterAnswerGenerator(String runId) {
        if (evidenceAnswerGenerator != null) evidenceAnswerGenerator.unregister(runId);
    }

    private EvidenceAnswerCommand evidenceAnswerCommand(ChatRequestDTO request,
                                                        RequestProbe probe,
                                                        String requestId) {
        String messageId = responseMessageId(request);
        String targetContext = safeList(request.getSelectedCellIds()).isEmpty()
                ? "" : "selectedCellIds=" + String.join(",", safeList(request.getSelectedCellIds()));
        return new EvidenceAnswerCommand(owner(request), request.getDiagramId(), request.getSessionId(),
                messageId, StringUtils.defaultIfBlank(request.getRequestId(), requestId), request.getRunId(),
                request.getMessage(), targetContext, answerConversationContext(request),
                probe.canvas().serverCanvasVersion(),
                probe.canvas().contentHash(), sourceMode(request.getSourceMode()) != SourceMode.EXPLICIT_ONLY);
    }

    private String answerConversationContext(ChatRequestDTO request) {
        if (diagramConversationStore == null || StringUtils.isBlank(request.getUserId())
                || StringUtils.isBlank(request.getDiagramId())) return "";
        try {
            List<org.zipp.ai.domain.agent.model.valobj.conversation.DiagramConversationMessage> stored =
                    diagramConversationStore.listMessages(request.getUserId(), request.getDiagramId());
            int start = Math.max(0, stored.size() - 6);
            String context = stored.subList(start, stored.size()).stream()
                    .map(message -> StringUtils.defaultString(message.getRole()) + ": "
                            + StringUtils.abbreviate(StringUtils.defaultString(message.getContent()), 500))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return StringUtils.abbreviate(context, 2400);
        } catch (RuntimeException unavailable) {
            // Conversation context improves follow-ups but is never allowed to weaken grounding.
            return "";
        }
    }

    private String responseMessageId(ChatRequestDTO request) {
        return StringUtils.defaultIfBlank(request.getResponseMessageId(),
                "answer-" + StringUtils.defaultIfBlank(request.getRequestId(), request.getRunId()));
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

    private DrawioToolAccessContext.ToolPolicy toolPolicyFor(IntentRoutingResult routingResult,
                                                             boolean drawerContinuation) {
        if (drawerContinuation) {
            // Reviewer feedback returns to the Drawer, which chooses the smallest suitable local tool.
            return DrawioToolAccessContext.ToolPolicy.of(
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM),
                    List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM, DrawioCanvasToolNames.OPTIMIZE_DIAGRAM));
        }
        String routeType = StringUtils.defaultString(routingResult.getRouteType());
        return switch (routeType) {
            case "create_new" -> phasedToolPolicy(DrawioCanvasToolNames.CREATE_DIAGRAM);
            case "edit_existing" -> phasedToolPolicy(DrawioCanvasToolNames.MODIFY_DIAGRAM);
            case "optimize_layout" -> phasedToolPolicy(DrawioCanvasToolNames.OPTIMIZE_DIAGRAM);
            case "review_only", "answer_only", "answer_with_evidence", "clarify" ->
                    DrawioToolAccessContext.ToolPolicy.of(List.of(), List.of());
            default -> DrawioToolAccessContext.ToolPolicy.of(List.of(), List.of());
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
            streamResponseWriter.rememberCitationBindings(emitter, json);

            String functionName = functionResponse.name().orElse(DrawioCanvasToolNames.CREATE_DIAGRAM);
            // Local patch responses return only the changed fragment; merge it into the canvas we already hold.
            if (DrawioCanvasToolNames.PATCH_CELLS.equals(functionName)
                    || DrawioCanvasToolNames.PATCH_CELLS.equals(json.getString("type"))) {
                String patchCells = json.getString("cells");
                boolean patchSent = streamResponseWriter.sendLocalCellPatch(
                        emitter, phase, currentCanvasXml, patchCells);
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
                if (json.containsKey("citationBindings")) {
                    toolJson.put("citationBindings", json.get("citationBindings"));
                }
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
        /** Canvas mutated and its repair brief authorizes the loop to finish. */
        CLEAN,
        /** Canvas mutated but a structural repair brief authorizes another tool turn. */
        NEEDS_REPAIR
    }

    private record CanvasMutationIntent(CanvasMutationPurpose purpose,
                                        CanvasMutationAuthorization authorization) {
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
            if ("tool_error".equals(type) || DrawioCanvasToolNames.NO_SAFE_CANDIDATE.equals(type)) {
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
            // repairBrief is the mutation authority: visual majors may keep analysis.valid=false,
            // but they must not start deterministic self-repair. Fall back only for legacy tools
            // that do not emit a repair brief yet.
            boolean clean = json.containsKey("repairBrief")
                    ? isFinishBrief(json.getString("repairBrief"))
                    : analysis == null || analysis.getBooleanValue("valid");
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
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
            streamResponseWriter.sendDone(emitter);
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, null);
            emitter.complete();
        } catch (Exception error) {
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, error);
            emitter.completeWithError(error);
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
                                      AgentUsageTelemetryService.StepScope drawingStep,
                                      AgentUsageTelemetryService.RunScope runScope,
                                      AtomicBoolean streamTelemetryCompleted) {
        if (manuallyCompleted.get()) {
            return;
        }
        clearSessionConfig(sessionId, runScope.getContext().runId());
        flushAuthorBuffers(emitter, authorBuffers);
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
            streamResponseWriter.sendDone(emitter);
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, null);
            emitter.complete();
        } catch (Exception error) {
            completeStreamTelemetry(streamTelemetryCompleted, drawingStep, runScope, error);
            emitter.completeWithError(error);
        }
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
