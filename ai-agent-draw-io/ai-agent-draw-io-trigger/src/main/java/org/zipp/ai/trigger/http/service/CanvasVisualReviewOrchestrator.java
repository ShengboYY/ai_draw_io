package org.zipp.ai.trigger.http.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
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

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CanvasVisualReviewOrchestrator {

    public static final String RENDERER_VERSION = "drawio-embed-png-v1";

    private final ICanvasStateStore canvasStateStore;
    private final ICanvasAnalyzer canvasAnalyzer;
    private final ICanvasVisualReviewer visualReviewer;
    private final CanvasReviewImageValidator imageValidator;
    private final AnonymousDemoQuotaService anonymousDemoQuotaService;
    private final VerifiedUserPlatformQuotaService verifiedUserPlatformQuotaService;
    private final AgentConversationService agentConversationService;
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
            CanvasReviewImageValidator.ValidatedImage before = StringUtils.isBlank(request.getBeforeImageDataUrl())
                    ? null : imageValidator.validate(request.getBeforeImageDataUrl());
            CanvasReviewImageValidator.ValidatedImage after = imageValidator.validate(request.getAfterImageDataUrl());
            CanvasVisualReviewStage stage = CanvasVisualReviewStage.valueOf(request.getStage());
            CanvasState reviewedState = canvasStateStore.find(ownerId, request.getDiagramId()).orElse(null);
            if (!matches(reviewedState, ownerId, request)) {
                sendStale(emitter, visualReviewRunId, request, reviewedState);
                sendDone(emitter, visualReviewRunId, request.getSourceRunId());
                emitter.complete();
                return;
            }

            // A visual review is a distinct model call and consumes the same owner quota as chat model work.
            anonymousDemoQuotaService.consumeIfNeeded(ownerId, null);
            verifiedUserPlatformQuotaService.consumeIfNeeded(ownerId, null);
            CanvasAnalysis analysis = canvasAnalyzer.analyze(reviewedState.getCurrentXml(), diagramType(request, reviewedState));
            sendMeta(emitter, visualReviewRunId, request);
            sendReviewStarted(emitter, visualReviewRunId, request, before, after);
            CanvasVisualReviewResult result = visualReviewer.review(command(request, reviewedState, stage, analysis));

            // Reject results for a canvas that changed while pixels were being reviewed.
            CanvasState latestState = canvasStateStore.find(ownerId, request.getDiagramId()).orElse(null);
            if (!matches(latestState, ownerId, request)) {
                sendStale(emitter, visualReviewRunId, request, latestState);
            } else {
                CanvasVisualReviewDecision decision = policy.decide(result, stage, stage == CanvasVisualReviewStage.VERIFY_ONLY ? 1 : 0);
                sendReviewResult(emitter, visualReviewRunId, request, result, decision);
                if (decision == CanvasVisualReviewDecision.REPAIR) {
                    // A single policy-approved continuation stays on this stream; the client never
                    // synthesizes another user message and VERIFY_ONLY can never enter this branch.
                    agentConversationService.streamVisualRepair(
                            repairRequest(ownerId, request, latestState, result),
                            diagramType(request, latestState),
                            shouldOptimizeLayout(result),
                            emitter);
                    return;
                }
            }
            sendDone(emitter, visualReviewRunId, request.getSourceRunId());
            emitter.complete();
        } catch (IllegalArgumentException e) {
            sendErrorAndComplete(emitter, "invalid_visual_review_request", e.getMessage());
        } catch (Exception e) {
            // VLM/provider details and image content must never be copied into the client error.
            sendErrorAndComplete(emitter, "visual_review_failed", "Visual review could not be completed.");
        }
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
        repair.setAgentId(request.getAgentId());
        repair.setSessionId(request.getSessionId());
        repair.setModelCredentialId(request.getModelCredentialId());
        repair.setRequestId("repair_req_" + UUID.randomUUID());
        repair.setRunId("repair_" + UUID.randomUUID());
        repair.setDiagramId(state.getDiagramId());
        repair.setExpectedVersion(state.getVersion());
        repair.setCanvasXml(state.getCurrentXml());
        repair.setMaxReviewIterations(1);
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
