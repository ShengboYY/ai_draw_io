package org.zipp.ai.infrastructure.turn.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.agent.DiagramDraftSnapshot;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualIssue;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReview;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReviewPort;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewDecision;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewGrounding;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewStage;
import org.zipp.ai.domain.agent.service.analysis.DrawioCellDocumentReader;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewGroundingGuard;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewPolicy;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;

import java.util.Base64;
import java.util.List;

/** Adapts the existing VLM reviewer as the delegated agent in the Plain review loop. */
@Component
@Slf4j
public final class DefaultDiagramDraftVisualReviewAdapter
        implements DiagramDraftVisualReviewPort {

    private static final String PNG_DATA_URL = "data:image/png;base64,";
    private final ICanvasVisualReviewer reviewer;
    private final DrawioCellDocumentReader cellReader = new DrawioCellDocumentReader();
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
            List<CanvasCellData> cells = cellReader.read(draft.canvasXml());
            DrawioDraftPngRenderer.RenderedDraft image = renderer.render(cells);
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
                            .analyzerEvidence(List.of())
                            .canvasSummary(canvasSummary(cells))
                            .canvasCells(cells)
                            .languageHint(languageHint(plan.instruction()))
                            .rendererVersion(image.rendererVersion())
                            .expectedContentHash(draft.digest())
                            .build());
            CanvasVisualReviewDecision decision =
                    policy.decide(result, CanvasVisualReviewStage.POST_MUTATION, 0);
            CanvasVisualReviewGrounding grounding = groundingGuard.ground(cells, result);
            boolean mixedTargetsAccepted = decision == CanvasVisualReviewDecision.REPAIR
                    && "mixed_target_capabilities".equals(grounding.conflictReason());
            if (decision == CanvasVisualReviewDecision.REPAIR
                    && grounding.hasConflict()
                    && !mixedTargetsAccepted) {
                // Ungrounded model findings remain visible evidence but cannot request an automatic patch.
                decision = CanvasVisualReviewDecision.NEEDS_HUMAN_REVIEW;
            }
            DiagramDraftVisualReview projected = new DiagramDraftVisualReview(
                    draft.digest(),
                    decision.name(),
                    result != null && result.isAvailable(),
                    bounded(result == null ? "" : result.getSummary(), 500),
                    issues(result),
                    mixedTargetsAccepted ? "" : grounding.conflictReason(),
                    bounded(result == null ? "" : result.getReviewerVersion(), 256));
            log.info(
                    "[plain-agent-review] digest={} decision={} available={} issues={} "
                            + "groundingConflict={} mixedTargetsAccepted={} latencyMs={} renderer={}",
                    draft.digest(),
                    projected.decision(),
                    projected.available(),
                    projected.issues().size(),
                    grounding.conflictReason(),
                    mixedTargetsAccepted,
                    elapsedMillis(started),
                    image.rendererVersion());
            return projected;
        } catch (RuntimeException failure) {
            log.warn(
                    "[plain-agent-review] digest={} decision=UNAVAILABLE reason={} latencyMs={}",
                    draft.digest(),
                    failure.getClass().getSimpleName(),
                    elapsedMillis(started));
            // The draft store already enforces XML safety and reference integrity.
            return DiagramDraftVisualReview.unavailable(
                    draft.digest(), "review_execution_failed");
        }
    }

    private String canvasSummary(List<CanvasCellData> cells) {
        long nodeCount = cells.stream()
                .filter(cell -> "node".equalsIgnoreCase(cell.getKind()))
                .count();
        long edgeCount = cells.stream()
                .filter(cell -> "edge".equalsIgnoreCase(cell.getKind()))
                .count();
        return "nodes=" + nodeCount + ", edges=" + edgeCount;
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
