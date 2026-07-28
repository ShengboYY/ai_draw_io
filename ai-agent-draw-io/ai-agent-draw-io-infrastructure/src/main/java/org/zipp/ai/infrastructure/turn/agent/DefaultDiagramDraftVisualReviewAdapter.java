package org.zipp.ai.infrastructure.turn.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.agent.DiagramDraftSnapshot;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualIssue;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReview;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReviewPort;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewGrounding;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewGroundingGuard;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;

import java.util.Base64;
import java.util.List;

/** Adapts the existing VLM reviewer to an attempt-scoped draft tool result. */
@Component
@Slf4j
public final class DefaultDiagramDraftVisualReviewAdapter
        implements DiagramDraftVisualReviewPort {

    private static final String PNG_DATA_URL = "data:image/png;base64,";
    private final ICanvasVisualReviewer reviewer;
    private final ICanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();
    private final DrawioDraftPngRenderer renderer = new DrawioDraftPngRenderer();
    private final CanvasVisualReviewPolicy policy = new CanvasVisualReviewPolicy();
    private final CanvasVisualReviewGroundingGuard groundingGuard =
            new CanvasVisualReviewGroundingGuard();

    public DefaultDiagramDraftVisualReviewAdapter(ICanvasVisualReviewer reviewer) {
        this.reviewer = reviewer;
    }

    @Override
    public DiagramDraftVisualReview review(
            PlainDrawPlan plan,
            DiagramDraftSnapshot draft
    ) {
        long started = System.nanoTime();
        try {
            CanvasAnalysis analysis = analyzer.analyze(draft.canvasXml(), plan.diagramType());
            DrawioDraftPngRenderer.RenderedDraft image = renderer.render(analysis);
            CanvasVisualReviewResult result = reviewer.review(
                    CanvasVisualReviewCommand.builder()
                            .stage(CanvasVisualReviewStage.POST_MUTATION)
                            .originalUserTask(plan.instruction())
                            .diagramType(plan.diagramType())
                            .afterImageDataUrl(PNG_DATA_URL
                                    + Base64.getEncoder().encodeToString(image.png()))
                            .totalPageCount(1)
                            .truncatedPageCount(0)
                            .additionalAfterImages(List.of())
                            .analyzerEvidence(analyzerEvidence(analysis))
                            .canvasSummary(canvasSummary(analysis))
                            .canvasCells(analysis.getCells())
                            .languageHint(languageHint(plan.instruction()))
                            .rendererVersion(image.rendererVersion())
                            .expectedContentHash(draft.digest())
                            .build());
            CanvasVisualReviewDecision decision =
                    policy.decide(result, CanvasVisualReviewStage.POST_MUTATION, 0);
            CanvasVisualReviewGrounding grounding = groundingGuard.ground(analysis, result);
            if (decision == CanvasVisualReviewDecision.REPAIR && grounding.hasConflict()) {
                // Ungrounded model findings remain visible evidence but cannot request an automatic patch.
                decision = CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
            }
            DiagramDraftVisualReview projected = new DiagramDraftVisualReview(
                    draft.digest(),
                    decision.name(),
                    result != null && result.isAvailable(),
                    bounded(result == null ? "" : result.getSummary(), 500),
                    issues(result),
                    grounding.conflictReason(),
                    bounded(result == null ? "" : result.getReviewerVersion(), 256));
            log.info(
                    "[plain-agent-review] digest={} decision={} available={} issues={} "
                            + "groundingConflict={} latencyMs={} renderer={}",
                    draft.digest(),
                    projected.decision(),
                    projected.available(),
                    projected.issues().size(),
                    projected.groundingConflict(),
                    elapsedMillis(started),
                    image.rendererVersion());
            return projected;
        } catch (RuntimeException failure) {
            log.warn(
                    "[plain-agent-review] digest={} decision=UNAVAILABLE reason={} latencyMs={}",
                    draft.digest(),
                    failure.getClass().getSimpleName(),
                    elapsedMillis(started));
            // Visual review is advisory; deterministic validation still decides canvas safety.
            return DiagramDraftVisualReview.unavailable(
                    draft.digest(), "review_execution_failed");
        }
    }

    private List<String> analyzerEvidence(CanvasAnalysis analysis) {
        return (analysis == null || analysis.getIssues() == null
                ? List.<CanvasAnalysisIssue>of()
                : analysis.getIssues()).stream()
                .limit(20)
                .map(issue -> issue.getType() + ":"
                        + String.join(",", issue.getTargetCellIds() == null
                        ? List.of()
                        : issue.getTargetCellIds()))
                .toList();
    }

    private String canvasSummary(CanvasAnalysis analysis) {
        if (analysis == null || analysis.getSummary() == null) {
            return "";
        }
        return bounded(analysis.getSummary().getSummary(), 500);
    }

    private List<DiagramDraftVisualIssue> issues(CanvasVisualReviewResult result) {
        return (result == null ? List.<CanvasVisualIssue>of() : result.safeIssues()).stream()
                .limit(5)
                .map(CanvasVisualIssue::boundedCopy)
                .map(issue -> new DiagramDraftVisualIssue(
                        issue.getType() == null ? "UNKNOWN" : issue.getType().name(),
                        issue.getSeverity() == null ? "" : issue.getSeverity().name(),
                        issue.getTargetCellIds(),
                        issue.getEvidence(),
                        issue.getRepairInstruction()))
                .toList();
    }

    private String languageHint(String instruction) {
        return instruction != null && instruction.codePoints()
                .anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                ? "zh"
                : "en";
    }

    private String bounded(String value, int limit) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
