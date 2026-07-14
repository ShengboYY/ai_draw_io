package org.zipp.ai.trigger.http.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
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
import java.util.UUID;

@Service
public class CanvasVisualReviewOrchestrator {

    public static final String RENDERER_VERSION = "drawio-embed-png-v1";
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
    private static final Set<CanvasVisualIssueType> LAYOUT_REPAIR_TYPES = Set.of(
            CanvasVisualIssueType.TEXT_READABILITY,
            CanvasVisualIssueType.LAYOUT_HIERARCHY,
            CanvasVisualIssueType.EDGE_TRACEABILITY,
            CanvasVisualIssueType.STYLE_COHERENCE);

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
            if (!visualReviewEnabled()) {
                // Disabled means no provider call, quota consumption, telemetry run, or user-visible review.
                sendMeta(emitter, visualReviewRunId, request);
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return;
            }
            CanvasReviewImageValidator.ValidatedImage before = StringUtils.isBlank(request.getBeforeImageDataUrl())
                    ? null : imageValidator.validate(request.getBeforeImageDataUrl());
            CanvasReviewImageValidator.ValidatedImage after = imageValidator.validate(request.getAfterImageDataUrl());
            CanvasVisualReviewStage stage = CanvasVisualReviewStage.valueOf(request.getStage());
            RepairContinuation repair = review(ownerId, visualReviewRunId, request, emitter, before, after, stage);
            if (repair == null) return;
            // The repair owns a separate run. Its terminal status and diagram snapshot provide
            // auto-repair success and after-repair hash without copying canvas content into review telemetry.
            agentConversationService.streamVisualRepair(
                    repair.request(),
                    repair.diagramType(),
                    repair.optimizeLayout(),
                    emitter);
        } catch (IllegalArgumentException e) {
            sendErrorAndComplete(emitter, "invalid_visual_review_request", e.getMessage());
        } catch (Exception e) {
            // VLM/provider details and image content must never be copied into the client error.
            sendErrorAndComplete(emitter, "visual_review_failed", "Visual review could not be completed.");
        }
    }

    private RepairContinuation review(String ownerId,
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
                    result, stage, stage == CanvasVisualReviewStage.VERIFY_ONLY ? 1 : 0);
            boolean shadow = Boolean.TRUE.equals(request.getShadow());
            if (decision == CanvasVisualReviewDecision.REPAIR && !autoRepairEnabled()) {
                // Visible-review rollout reports the same evidence without granting mutation authority.
                decision = CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
            }
            Map<String, Object> completed = completedMetadata(request, result, decision, reviewLatencyMs);
            if (!shadow) {
                sendReviewResult(emitter, visualReviewRunId, request, result, decision);
            }
            if (shadow || decision != CanvasVisualReviewDecision.REPAIR) {
                recordReviewEvent(run, result != null && result.isAvailable()
                        ? "visual_review_completed" : "visual_review_unavailable", "SUCCESS", completed);
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return null;
            }

            ChatRequestDTO repair = repairRequest(ownerId, request, latestState, result);
            completed.put("autoRepairAttempted", true);
            completed.put("repairRunId", repair.getRunId());
            recordReviewEvent(run, "visual_review_completed", "SUCCESS", completed);
            return new RepairContinuation(repair, diagramType(request, latestState), shouldOptimizeLayout(result));
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

    private record RepairContinuation(ChatRequestDTO request, String diagramType, boolean optimizeLayout) {
    }

    private void validateRequest(String ownerId, CanvasVisualReviewRequestDTO request) {
        if (StringUtils.isBlank(ownerId) || request == null || StringUtils.isBlank(request.getAgentId())
                || StringUtils.isBlank(request.getSessionId()) || StringUtils.isBlank(request.getDiagramId())
                || request.getExpectedVersion() == null || StringUtils.isBlank(request.getExpectedContentHash())
                || StringUtils.isBlank(request.getAfterImageDataUrl()) || StringUtils.isBlank(request.getStage())
                || StringUtils.isBlank(request.getSourceRunId()) || StringUtils.isBlank(request.getOriginalUserTask())
                || !RENDERER_VERSION.equals(request.getRendererVersion())) {
            throw new IllegalArgumentException("missing_or_invalid_fields");
        }
        try {
            CanvasVisualReviewStage.valueOf(request.getStage());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid_stage", e);
        }
    }

    private ChatRequestDTO repairRequest(String ownerId,
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
        repair.setRunId("aru_repair_" + UUID.randomUUID());
        repair.setDiagramId(state.getDiagramId());
        repair.setExpectedVersion(state.getVersion());
        repair.setCanvasXml(state.getCurrentXml());
        repair.setMessage(repairBriefComposer.compose(
                request.getOriginalUserTask(), state.getVersion(), state.getContentHash(), result.safeIssues()));
        return repair;
    }

    private boolean shouldOptimizeLayout(CanvasVisualReviewResult result) {
        List<CanvasVisualIssue> blocking = result.safeIssues().stream()
                .filter(issue -> issue.getSeverity() != null && issue.getSeverity().isBlocking())
                .toList();
        return !blocking.isEmpty() && blocking.stream()
                .allMatch(issue -> LAYOUT_REPAIR_TYPES.contains(issue.getType()));
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
}
