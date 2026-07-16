package org.zipp.ai.trigger.http.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasRepairScope;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.model.valobj.visualreview.DrawerContinuationContext;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualRepairBriefComposer;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import javax.annotation.Resource;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class CanvasVisualReviewOrchestrator {

    public static final String RENDERER_VERSION = "drawio-embed-png-v1";
    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final AgentUsageTelemetryService NOOP_TELEMETRY =
            new AgentUsageTelemetryService(null, Clock.systemUTC());

    private final ICanvasStateStore canvasStateStore;
    private final ICanvasAnalyzer canvasAnalyzer;
    private final ICanvasVisualReviewer visualReviewer;
    private final CanvasReviewImageValidator imageValidator;
    private final AnonymousDemoQuotaService anonymousDemoQuotaService;
    private final VerifiedUserPlatformQuotaService verifiedUserPlatformQuotaService;
    private final AgentConversationService agentConversationService;
    @Resource
    private AgentUsageTelemetryService agentUsageTelemetryService;
    @Resource
    private VisualReviewRolloutPolicy visualReviewRolloutPolicy;
    @Value("${zipp.visual-review.drawer-agent-id:300000}")
    private String drawerAgentId = "300000";
    private final CanvasVisualReviewPolicy policy = new CanvasVisualReviewPolicy();
    private final CanvasVisualRepairBriefComposer repairBriefComposer = new CanvasVisualRepairBriefComposer();

    public CanvasVisualReviewOrchestrator(ICanvasStateStore canvasStateStore,
                                          ICanvasAnalyzer canvasAnalyzer,
                                          ICanvasVisualReviewer visualReviewer,
                                          CanvasReviewImageValidator imageValidator,
                                          AnonymousDemoQuotaService anonymousDemoQuotaService,
                                          VerifiedUserPlatformQuotaService verifiedUserPlatformQuotaService,
                                          AgentConversationService agentConversationService) {
        this.canvasStateStore = canvasStateStore;
        this.canvasAnalyzer = canvasAnalyzer;
        this.visualReviewer = visualReviewer;
        this.imageValidator = imageValidator;
        this.anonymousDemoQuotaService = anonymousDemoQuotaService;
        this.verifiedUserPlatformQuotaService = verifiedUserPlatformQuotaService;
        this.agentConversationService = agentConversationService;
    }

    public void stream(String ownerId,
                       String visualReviewRunId,
                       CanvasVisualReviewRequestDTO request,
                       ResponseBodyEmitter emitter) {
        try {
            validateRequest(ownerId, request);
            log.info("[visual-review-loop] event=request reviewRunId={} sourceRunId={} parentRunId={} repairRound={} stage={} diagramId={} expectedVersion={} expectedHash={} shadow={}",
                    logValue(visualReviewRunId), logValue(request.getSourceRunId()),
                    logValue(request.getParentRunId()), visualRepairRound(request), request.getStage(),
                    logValue(request.getDiagramId()), request.getExpectedVersion(),
                    logValue(request.getExpectedContentHash()), Boolean.TRUE.equals(request.getShadow()));
            if (!visualReviewEnabled()) {
                // Disabled means no provider call, quota consumption, telemetry run, or user-visible review.
                log.info("[visual-review-loop] event=complete reviewRunId={} stage={} repairRound={} outcome=disabled",
                        logValue(visualReviewRunId), request.getStage(), visualRepairRound(request));
                sendMeta(emitter, visualReviewRunId, request);
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return;
            }
            CanvasReviewImageValidator.ValidatedImage before = StringUtils.isBlank(request.getBeforeImageDataUrl())
                    ? null : imageValidator.validate(request.getBeforeImageDataUrl());
            CanvasReviewImageValidator.ValidatedImage after = imageValidator.validate(request.getAfterImageDataUrl());
            CanvasVisualReviewStage stage = CanvasVisualReviewStage.valueOf(request.getStage());
            DrawerContinuation continuation = review(
                    ownerId, visualReviewRunId, request, emitter, before, after, stage);
            if (continuation == null) return;
            // The continuation owns a separate run while reusing the original Drawer agent and session.
            log.info("[visual-review-loop] event=drawer_continuation reviewRunId={} repairRunId={} sourceRunId={} parentRunId={} repairRound={} diagramId={} expectedVersion={} expectedHash={} authorizedCells={}",
                    logValue(visualReviewRunId), logValue(continuation.request().getRunId()),
                    logValue(continuation.request().getSourceRunId()), logValue(continuation.request().getParentRunId()),
                    continuation.request().getVisualRepairRound(), logValue(continuation.request().getDiagramId()),
                    continuation.request().getExpectedVersion(), logValue(continuation.request().getExpectedContentHash()),
                    continuation.context().authorization().allowedCellIds().size());
            agentConversationService.continueDrawing(
                    continuation.request(), continuation.context(), emitter);
        } catch (IllegalArgumentException e) {
            log.warn("[visual-review-loop] event=rejected reviewRunId={} errorClass={}",
                    logValue(visualReviewRunId), e.getClass().getSimpleName());
            sendErrorAndComplete(emitter, "invalid_visual_review_request", e.getMessage());
        } catch (Exception e) {
            // VLM/provider details and image content must never be copied into the client error.
            log.warn("[visual-review-loop] event=failed reviewRunId={} sourceRunId={} stage={} repairRound={} errorClass={}",
                    logValue(visualReviewRunId), logValue(request == null ? null : request.getSourceRunId()),
                    request == null ? "" : logValue(request.getStage()),
                    request == null ? -1 : visualRepairRound(request), e.getClass().getSimpleName());
            sendErrorAndComplete(emitter, "visual_review_failed", "Visual review could not be completed.");
        }
    }

    private DrawerContinuation review(String ownerId,
                                      String visualReviewRunId,
                                      CanvasVisualReviewRequestDTO request,
                                      ResponseBodyEmitter emitter,
                                      CanvasReviewImageValidator.ValidatedImage before,
                                      CanvasReviewImageValidator.ValidatedImage after,
                                      CanvasVisualReviewStage stage) throws Exception {
        AgentUsageTelemetryService.RunScope run = telemetryService().startRun(
                visualReviewRunId, request.getRequestId(), ownerId, visualReviewer.agentId(), request.getSessionId(),
                "visual_review", request.getDiagramId(), AgentUsageTelemetryService.PLATFORM,
                null, "openai", visualReviewer.version());
        Throwable failure = null;
        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(run.getContext())) {
            CanvasState reviewedState = canvasStateStore.find(ownerId, request.getDiagramId()).orElse(null);
            recordReviewEvent(run, "visual_review_started", "RUNNING",
                    startedMetadata(request, before, after));
            sendMeta(emitter, visualReviewRunId, request);
            if (!matches(reviewedState, ownerId, request)) {
                recordStale(run, request, reviewedState, "before_provider");
                sendStale(emitter, visualReviewRunId, request, reviewedState);
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return null;
            }
            if (stage == CanvasVisualReviewStage.POST_REPAIR
                    || stage == CanvasVisualReviewStage.VERIFY_ONLY) {
                boolean verifiedLineage = telemetryService().isVisualRepairResult(
                        request.getSourceRunId(), request.getParentRunId(), ownerId, request.getDiagramId(),
                        request.getExpectedVersion(), request.getExpectedContentHash(), visualRepairRound(request));
                log.info("[visual-review-loop] event=verify_lineage reviewRunId={} sourceRunId={} parentRunId={} diagramId={} repairRound={} version={} hash={} verified={}",
                        logValue(visualReviewRunId), logValue(request.getSourceRunId()),
                        logValue(request.getParentRunId()), logValue(request.getDiagramId()),
                        visualRepairRound(request), request.getExpectedVersion(),
                        logValue(request.getExpectedContentHash()), verifiedLineage);
                if (!verifiedLineage) {
                    throw new IllegalArgumentException("invalid_visual_repair_lineage");
                }
            }

            // A visual review is a distinct model call and consumes the same owner quota as chat model work.
            anonymousDemoQuotaService.consumeIfNeeded(ownerId, null);
            verifiedUserPlatformQuotaService.consumeIfNeeded(ownerId, null);
            CanvasAnalysis analysis = canvasAnalyzer.analyze(reviewedState.getCurrentXml(), diagramType(request, reviewedState));
            sendReviewStarted(emitter, visualReviewRunId, request, before, after);
            long startedNanos = System.nanoTime();
            CanvasVisualReviewResult result = visualReviewer.review(command(request, reviewedState, stage, analysis));
            long reviewLatencyMs = (System.nanoTime() - startedNanos) / 1_000_000;

            // Reject results for a canvas that changed while pixels were being reviewed.
            CanvasState latestState = canvasStateStore.find(ownerId, request.getDiagramId()).orElse(null);
            if (!matches(latestState, ownerId, request)) {
                recordStale(run, request, latestState, "after_provider");
                sendStale(emitter, visualReviewRunId, request, latestState);
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return null;
            }

            CanvasVisualReviewDecision decision = policy.decide(
                    result, stage, visualRepairRound(request));
            CanvasMutationAuthorization authorization = repairAuthorization(analysis, result);
            boolean shadow = Boolean.TRUE.equals(request.getShadow());
            if (!shadow && decision == CanvasVisualReviewDecision.REPAIR
                    && authorization.allowedCellIds().isEmpty()) {
                // Phase 3 fails closed until a deterministic issue can locate the visual finding.
                decision = CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
            }
            if (decision == CanvasVisualReviewDecision.REPAIR && !autoRepairEnabled()) {
                // Visible-review rollout reports the same evidence without granting mutation authority.
                decision = CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
            }
            String repairRunId = !shadow && decision == CanvasVisualReviewDecision.REPAIR
                    ? "aru_repair_" + UUID.randomUUID() : null;
            if (!shadow && decision == CanvasVisualReviewDecision.REPAIR) {
                int nextRepairRound = visualRepairRound(request) + 1;
                boolean claimed = telemetryService().tryClaimVisualRepair(
                        request.getSourceRunId(), request.getParentRunId(), ownerId, request.getDiagramId(),
                        request.getRequestId(), request.getExpectedVersion(), request.getExpectedContentHash(),
                        nextRepairRound, repairRunId);
                log.info("[visual-review-loop] event=repair_claim reviewRunId={} sourceRunId={} diagramId={} repairRound={} granted={}",
                        logValue(visualReviewRunId), logValue(request.getSourceRunId()),
                        logValue(request.getDiagramId()), nextRepairRound, claimed);
                if (!claimed) {
                    // Missing ownership and replayed source runs both fail closed without mutating the canvas.
                    decision = CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
                }
            }
            if (stage == CanvasVisualReviewStage.VERIFY_ONLY
                    && decision == CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW) {
                // Final verification may report unresolved findings, but the mutation budget is closed.
                log.info("[visual-review-loop] event=repair_budget_exhausted reviewRunId={} sourceRunId={} repairRound={} issues={}",
                        logValue(visualReviewRunId), logValue(request.getSourceRunId()),
                        visualRepairRound(request), result == null ? 0 : result.safeIssues().size());
            }
            log.info("[visual-review-loop] event=decision reviewRunId={} sourceRunId={} repairRound={} stage={} available={} decision={} issues={} authorizedCells={} latencyMs={}",
                    logValue(visualReviewRunId), logValue(request.getSourceRunId()), visualRepairRound(request),
                    request.getStage(), result != null && result.isAvailable(), decision,
                    result == null ? 0 : result.safeIssues().size(), authorization.allowedCellIds().size(),
                    reviewLatencyMs);
            Map<String, Object> completed = completedMetadata(request, result, decision, reviewLatencyMs);
            if (!shadow) {
                sendReviewResult(emitter, visualReviewRunId, request, result, decision);
            }
            if (shadow || decision != CanvasVisualReviewDecision.REPAIR) {
                recordReviewEvent(run, result != null && result.isAvailable()
                        ? "visual_review_completed" : "visual_review_unavailable", "SUCCESS", completed);
                log.info("[visual-review-loop] event=complete reviewRunId={} sourceRunId={} repairRound={} stage={} outcome={}",
                        logValue(visualReviewRunId), logValue(request.getSourceRunId()), visualRepairRound(request),
                        request.getStage(), shadow ? "shadow" : decision.name());
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return null;
            }

            ChatRequestDTO repair = repairRequest(
                    ownerId, visualReviewRunId, repairRunId, request, latestState, result);
            completed.put("autoRepairAttempted", true);
            completed.put("repairRunId", repair.getRunId());
            recordReviewEvent(run, "visual_review_completed", "SUCCESS", completed);
            return new DrawerContinuation(
                    repair,
                    new DrawerContinuationContext(
                            diagramType(request, latestState),
                            authorization));
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            telemetryService().completeRun(run, failure);
        }
    }

    private Map<String, Object> startedMetadata(CanvasVisualReviewRequestDTO request,
                                                CanvasReviewImageValidator.ValidatedImage before,
                                                CanvasReviewImageValidator.ValidatedImage after) {
        Map<String, Object> metadata = baseMetadata(request);
        metadata.put("reviewerVersion", visualReviewer.version());
        metadata.put("rendererVersion", request.getRendererVersion());
        metadata.put("beforeImageBytes", before == null ? 0 : before.bytes().length);
        metadata.put("beforeImageWidth", before == null ? 0 : before.width());
        metadata.put("beforeImageHeight", before == null ? 0 : before.height());
        metadata.put("afterImageBytes", after.bytes().length);
        metadata.put("afterImageWidth", after.width());
        metadata.put("afterImageHeight", after.height());
        return metadata;
    }

    private Map<String, Object> completedMetadata(CanvasVisualReviewRequestDTO request,
                                                  CanvasVisualReviewResult result,
                                                  CanvasVisualReviewDecision decision,
                                                  long reviewLatencyMs) {
        Map<String, Object> metadata = baseMetadata(request);
        metadata.put("reviewerVersion", result == null
                ? visualReviewer.version() : StringUtils.defaultIfBlank(result.getReviewerVersion(), visualReviewer.version()));
        metadata.put("latencyMs", reviewLatencyMs);
        metadata.put("available", result != null && result.isAvailable());
        metadata.put("unavailableReason", result == null ? "missing_result"
                : StringUtils.defaultString(result.getUnavailableReason()));
        metadata.put("decision", decision.name());
        metadata.put("recommendedHumanReview", result != null && result.isRecommendedHumanReview());
        metadata.put("issueTypeCounts", issueTypeCounts(result));
        metadata.put("issueSeverityCounts", issueSeverityCounts(result));
        metadata.put("autoRepairAttempted", false);
        metadata.put("autoRepairEnabled", autoRepairEnabled());
        boolean repairVerification = CanvasVisualReviewStage.VERIFY_ONLY.name().equals(request.getStage());
        metadata.put("repairBudgetExhausted", repairVerification
                && decision == CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW);
        boolean repairSucceeded = repairVerification
                && (decision == CanvasVisualReviewDecision.APPROVE
                || decision == CanvasVisualReviewDecision.APPROVE_WITH_NOTES);
        metadata.put("autoRepairSucceeded", repairSucceeded);
        metadata.put("afterRepairCanvasHash", repairVerification ? request.getExpectedContentHash() : "");
        return metadata;
    }

    private Map<String, Object> baseMetadata(CanvasVisualReviewRequestDTO request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceRunId", request.getSourceRunId());
        metadata.put("parentRunId", StringUtils.defaultString(request.getParentRunId()));
        metadata.put("visualRepairRound", visualRepairRound(request));
        metadata.put("stage", request.getStage());
        metadata.put("expectedVersion", request.getExpectedVersion());
        metadata.put("beforeCanvasHash", StringUtils.defaultString(request.getBeforeContentHash()));
        metadata.put("reviewedCanvasHash", request.getExpectedContentHash());
        metadata.put("shadow", Boolean.TRUE.equals(request.getShadow()));
        return metadata;
    }

    private Map<String, Integer> issueTypeCounts(CanvasVisualReviewResult result) {
        Map<CanvasVisualIssueType, Integer> counts = new EnumMap<>(CanvasVisualIssueType.class);
        if (result != null) {
            result.safeIssues().stream().map(CanvasVisualIssue::getType).filter(Objects::nonNull)
                    .forEach(type -> counts.merge(type, 1, Integer::sum));
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        counts.forEach((type, count) -> values.put(type.name(), count));
        return values;
    }

    private Map<String, Integer> issueSeverityCounts(CanvasVisualReviewResult result) {
        Map<CanvasVisualIssueSeverity, Integer> counts = new EnumMap<>(CanvasVisualIssueSeverity.class);
        if (result != null) {
            result.safeIssues().stream().map(CanvasVisualIssue::getSeverity).filter(Objects::nonNull)
                    .forEach(severity -> counts.merge(severity, 1, Integer::sum));
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        counts.forEach((severity, count) -> values.put(severity.name(), count));
        return values;
    }

    private void recordStale(AgentUsageTelemetryService.RunScope run,
                             CanvasVisualReviewRequestDTO request,
                             CanvasState current,
                             String detectedAt) {
        Map<String, Object> metadata = baseMetadata(request);
        metadata.put("detectedAt", detectedAt);
        metadata.put("currentVersion", current == null ? -1 : current.getVersion());
        metadata.put("currentCanvasHash", current == null ? "" : StringUtils.defaultString(current.getContentHash()));
        recordReviewEvent(run, "visual_review_stale", "SUCCESS", metadata);
        log.info("[visual-review-loop] event=stale reviewRunId={} sourceRunId={} repairRound={} stage={} detectedAt={} expectedVersion={} currentVersion={} expectedHash={} currentHash={}",
                logValue(run.getContext().runId()), logValue(request.getSourceRunId()), visualRepairRound(request),
                request.getStage(), detectedAt, request.getExpectedVersion(),
                current == null ? null : current.getVersion(), logValue(request.getExpectedContentHash()),
                logValue(current == null ? null : current.getContentHash()));
    }

    private void recordReviewEvent(AgentUsageTelemetryService.RunScope run,
                                   String eventType,
                                   String status,
                                   Map<String, ?> metadata) {
        telemetryService().recordTraceEvent(run.getContext(), eventType, "visual_review", status, metadata);
    }

    private AgentUsageTelemetryService telemetryService() {
        return agentUsageTelemetryService == null ? NOOP_TELEMETRY : agentUsageTelemetryService;
    }

    private boolean visualReviewEnabled() {
        // Plain unit tests construct the service outside Spring; preserve the pre-rollout behavior there.
        return visualReviewRolloutPolicy == null || visualReviewRolloutPolicy.isEnabled();
    }

    private boolean autoRepairEnabled() {
        return visualReviewRolloutPolicy == null || visualReviewRolloutPolicy.isAutoRepairEnabled();
    }

    private CanvasMutationAuthorization repairAuthorization(CanvasAnalysis analysis,
                                                             CanvasVisualReviewResult result) {
        Set<String> cellIds = new java.util.LinkedHashSet<>();
        Set<String> anchorLabels = result == null ? Set.of() : result.safeIssues().stream()
                .flatMap(issue -> issue.getAnchorLabels() == null
                        ? java.util.stream.Stream.empty()
                        : issue.getAnchorLabels().stream())
                .filter(StringUtils::isNotBlank)
                .map(label -> label.trim().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        if (analysis != null && analysis.getCells() != null && !anchorLabels.isEmpty()) {
            Map<String, List<org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData>> cellsByLabel =
                    analysis.getCells().stream()
                            .filter(cell -> StringUtils.isNotBlank(cell.getId()))
                            .collect(Collectors.groupingBy(cell -> StringUtils.defaultString(cell.getLabel())
                                    .trim().toLowerCase(java.util.Locale.ROOT)));
            // Until Phase 5 adds the full VisualIssueLocator, only a unique exact label grants authority.
            anchorLabels.forEach(label -> {
                List<org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData> matches = cellsByLabel.get(label);
                if (matches != null && matches.size() == 1) {
                    cellIds.add(matches.get(0).getId());
                }
            });
        }
        return new CanvasMutationAuthorization(
                cellIds,
                Set.of(CanvasField.STYLE, CanvasField.GEOMETRY, CanvasField.WAYPOINTS),
                CanvasRepairScope.TARGET_CELLS);
    }

    private record DrawerContinuation(ChatRequestDTO request,
                                      DrawerContinuationContext context) {
    }

    private void validateRequest(String ownerId, CanvasVisualReviewRequestDTO request) {
        if (StringUtils.isBlank(ownerId) || request == null || StringUtils.isBlank(request.getAgentId())
                || StringUtils.isBlank(request.getSessionId()) || StringUtils.isBlank(request.getDiagramId())
                || request.getExpectedVersion() == null || StringUtils.isBlank(request.getExpectedContentHash())
                || StringUtils.isBlank(request.getAfterImageDataUrl()) || StringUtils.isBlank(request.getStage())
                || StringUtils.isBlank(request.getSourceRunId()) || StringUtils.isBlank(request.getParentRunId())
                || request.getVisualRepairRound() == null || StringUtils.isBlank(request.getOriginalUserTask())
                || !RENDERER_VERSION.equals(request.getRendererVersion())) {
            throw new IllegalArgumentException("missing_or_invalid_fields");
        }
        if (!CORRELATION_ID.matcher(request.getSourceRunId()).matches()
                || !CORRELATION_ID.matcher(request.getParentRunId()).matches()) {
            throw new IllegalArgumentException("invalid_lineage_id");
        }
        CanvasVisualReviewStage stage;
        try {
            stage = CanvasVisualReviewStage.valueOf(request.getStage());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_stage", e);
        }
        int expectedRound = switch (stage) {
            case CURRENT_CANVAS, POST_MUTATION -> 0;
            case POST_REPAIR -> 1;
            case VERIFY_ONLY -> CanvasVisualReviewPolicy.MAX_AUTOMATIC_REPAIR_ROUNDS;
        };
        if (request.getVisualRepairRound() != expectedRound) {
            throw new IllegalArgumentException("invalid_visual_repair_round");
        }
        if ((stage == CanvasVisualReviewStage.CURRENT_CANVAS || stage == CanvasVisualReviewStage.POST_MUTATION)
                && !Objects.equals(request.getSourceRunId(), request.getParentRunId())) {
            throw new IllegalArgumentException("invalid_visual_review_parent");
        }
    }

    private ChatRequestDTO repairRequest(String ownerId,
                                         String visualReviewRunId,
                                         String repairRunId,
                                         CanvasVisualReviewRequestDTO request,
                                         CanvasState state,
                                         CanvasVisualReviewResult result) {
        ChatRequestDTO repair = new ChatRequestDTO();
        repair.setUserId(ownerId);
        // Repair authority is server-owned; never let a client select an agent with broader tools.
        repair.setAgentId(drawerAgentId);
        repair.setSessionId(request.getSessionId());
        repair.setModelCredentialId(request.getModelCredentialId());
        repair.setRequestId("repair_req_" + UUID.randomUUID());
        // Keep the correlation id stable when it crosses the telemetry normalization boundary.
        repair.setRunId(repairRunId);
        repair.setSourceRunId(request.getSourceRunId());
        repair.setParentRunId(visualReviewRunId);
        // The Drawer receives the numbered mutation attempt granted by the server-side policy.
        repair.setVisualRepairRound(visualRepairRound(request) + 1);
        repair.setDiagramId(state.getDiagramId());
        repair.setExpectedVersion(state.getVersion());
        repair.setExpectedContentHash(state.getContentHash());
        repair.setCanvasXml(state.getCurrentXml());
        repair.setMessage(repairBriefComposer.compose(
                request.getOriginalUserTask(), state.getVersion(), state.getContentHash(), result.safeIssues()));
        return repair;
    }

    private CanvasVisualReviewCommand command(CanvasVisualReviewRequestDTO request,
                                              CanvasState state,
                                              CanvasVisualReviewStage stage,
                                              CanvasAnalysis analysis) {
        List<String> evidence = analysis == null || analysis.getIssues() == null
                ? Collections.emptyList()
                : analysis.getIssues().stream().limit(10).map(CanvasAnalysisIssue::getMessage)
                .filter(StringUtils::isNotBlank).toList();
        String summary = analysis == null || analysis.getSummary() == null ? "" : analysis.getSummary().getSummary();
        return CanvasVisualReviewCommand.builder()
                .stage(stage)
                .originalUserTask(request.getOriginalUserTask())
                .diagramType(diagramType(request, state))
                .beforeImageDataUrl(request.getBeforeImageDataUrl())
                .afterImageDataUrl(request.getAfterImageDataUrl())
                .analyzerEvidence(evidence)
                .canvasSummary(summary)
                .rendererVersion(request.getRendererVersion())
                .expectedVersion(request.getExpectedVersion())
                .expectedContentHash(request.getExpectedContentHash())
                .build();
    }

    private boolean matches(CanvasState state, String ownerId, CanvasVisualReviewRequestDTO request) {
        return state != null
                && Objects.equals(ownerId, state.getUserId())
                && Objects.equals(request.getDiagramId(), state.getDiagramId())
                && Objects.equals(request.getExpectedVersion(), state.getVersion())
                && Objects.equals(request.getExpectedContentHash(), state.getContentHash());
    }

    private String diagramType(CanvasVisualReviewRequestDTO request, CanvasState state) {
        return StringUtils.defaultIfBlank(request.getDiagramType(), state == null ? "none" : state.getDiagramType());
    }

    private void sendReviewStarted(ResponseBodyEmitter emitter,
                                   String runId,
                                   CanvasVisualReviewRequestDTO request,
                                   CanvasReviewImageValidator.ValidatedImage before,
                                   CanvasReviewImageValidator.ValidatedImage after) throws Exception {
        JSONObject chunk = chunk("review_started");
        chunk.put("stage", request.getStage());
        chunk.put("sourceRunId", request.getSourceRunId());
        chunk.put("parentRunId", request.getParentRunId());
        chunk.put("visualRepairRound", visualRepairRound(request));
        chunk.put("visualReviewRunId", runId);
        chunk.put("beforeImageBytes", before == null ? 0 : before.bytes().length);
        chunk.put("afterImageBytes", after.bytes().length);
        chunk.put("afterImageWidth", after.width());
        chunk.put("afterImageHeight", after.height());
        send(emitter, chunk);
    }

    private void sendMeta(ResponseBodyEmitter emitter,
                          String runId,
                          CanvasVisualReviewRequestDTO request) throws Exception {
        JSONObject chunk = chunk("meta");
        chunk.put("sourceRunId", request.getSourceRunId());
        chunk.put("parentRunId", request.getParentRunId());
        chunk.put("visualRepairRound", visualRepairRound(request));
        chunk.put("visualReviewRunId", runId);
        chunk.put("diagramId", request.getDiagramId());
        chunk.put("expectedVersion", request.getExpectedVersion());
        send(emitter, chunk);
    }

    private void sendReviewResult(ResponseBodyEmitter emitter,
                                  String runId,
                                  CanvasVisualReviewRequestDTO request,
                                  CanvasVisualReviewResult result,
                                  CanvasVisualReviewDecision decision) throws Exception {
        JSONObject chunk = chunk("review_result");
        chunk.put("approved", decision == CanvasVisualReviewDecision.APPROVE
                || decision == CanvasVisualReviewDecision.APPROVE_WITH_NOTES);
        chunk.put("available", result != null && result.isAvailable());
        chunk.put("decision", decision.name());
        chunk.put("stage", request.getStage());
        chunk.put("visualRepairRound", visualRepairRound(request));
        chunk.put("content", result == null ? "" : StringUtils.defaultString(result.getSummary()));
        chunk.put("issues", issueArray(result));
        chunk.put("recommendedHumanReview", result != null && result.isRecommendedHumanReview());
        chunk.put("sourceRunId", request.getSourceRunId());
        chunk.put("visualReviewRunId", runId);
        send(emitter, chunk);
    }

    private JSONArray issueArray(CanvasVisualReviewResult result) {
        JSONArray issues = new JSONArray();
        if (result == null) return issues;
        for (CanvasVisualIssue rawIssue : result.safeIssues().stream().limit(5).toList()) {
            CanvasVisualIssue issue = rawIssue.boundedCopy();
            JSONObject value = new JSONObject();
            value.put("type", issue.getType() == null ? "" : issue.getType().name());
            value.put("severity", issue.getSeverity() == null ? "" : issue.getSeverity().name().toLowerCase());
            value.put("anchorLabels", issue.getAnchorLabels());
            value.put("region", issue.getRegion());
            value.put("evidence", issue.getEvidence());
            value.put("repairInstruction", issue.getRepairInstruction());
            value.put("repairScope", issue.getRepairScope() == null
                    ? "" : issue.getRepairScope().name().toLowerCase());
            issues.add(value);
        }
        return issues;
    }

    private void sendStale(ResponseBodyEmitter emitter,
                           String runId,
                           CanvasVisualReviewRequestDTO request,
                           CanvasState current) throws Exception {
        JSONObject chunk = chunk("review_stale");
        chunk.put("sourceRunId", request.getSourceRunId());
        chunk.put("visualReviewRunId", runId);
        chunk.put("expectedVersion", request.getExpectedVersion());
        chunk.put("currentVersion", current == null ? null : current.getVersion());
        send(emitter, chunk);
    }

    private void sendDone(ResponseBodyEmitter emitter, String runId, String sourceRunId) throws Exception {
        JSONObject chunk = chunk("done");
        chunk.put("visualReviewRunId", runId);
        chunk.put("sourceRunId", sourceRunId);
        send(emitter, chunk);
    }

    private void sendErrorAndComplete(ResponseBodyEmitter emitter, String code, String message) {
        try {
            JSONObject chunk = chunk("error");
            chunk.put("code", code);
            chunk.put("content", StringUtils.defaultIfBlank(message, "Invalid visual review request."));
            send(emitter, chunk);
            emitter.complete();
        } catch (Exception sendError) {
            emitter.completeWithError(sendError);
        }
    }

    private JSONObject chunk(String type) {
        JSONObject chunk = new JSONObject();
        chunk.put("type", type);
        return chunk;
    }

    private void send(ResponseBodyEmitter emitter, JSONObject chunk) throws Exception {
        JSONObject envelope = new JSONObject();
        envelope.put("phase", "visual_review");
        envelope.put("chunk", chunk);
        emitter.send(envelope.toJSONString() + "\n");
    }

    private int visualRepairRound(CanvasVisualReviewRequestDTO request) {
        Integer requestedRound = request == null ? null : request.getVisualRepairRound();
        if (requestedRound != null) {
            return requestedRound;
        }
        return 0;
    }

    private String logValue(String value) {
        String compact = StringUtils.defaultString(value).replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "...";
    }
}
