package org.zipp.ai.infrastructure.turn.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentToolPort;
import org.zipp.ai.application.turn.agent.DiagramAgentToolRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentToolResult;
import org.zipp.ai.application.turn.agent.DiagramDraftAnalysis;
import org.zipp.ai.application.turn.agent.DiagramDraftIssue;
import org.zipp.ai.application.turn.agent.DiagramDraftSnapshot;
import org.zipp.ai.application.turn.agent.DiagramDraftStore;
import org.zipp.ai.application.turn.agent.DiagramDraftVisualReviewPort;
import org.zipp.ai.application.turn.agent.DiagramDraftView;
import org.zipp.ai.application.turn.agent.DraftInspectionScope;
import org.zipp.ai.application.turn.agent.InspectDraftRequest;
import org.zipp.ai.application.turn.agent.InspectedDiagramCell;
import org.zipp.ai.application.turn.agent.PatchDraftRequest;
import org.zipp.ai.application.turn.agent.ReviewDraftRequest;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasIssueType;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** New V2 tool surface over the draft store; no legacy MCP tool or ADK session is reused. */
@Component
public final class DefaultDiagramAgentToolAdapter implements DiagramAgentToolPort {

    private static final int MAX_INSPECTED_CELLS = 256;
    private static final int MAX_RAW_CELL_XML_CHARS = 20_000;
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z0-9_]{3,80}");
    private static final Set<String> LAYOUT_STYLE_KEYS = Set.of(
            "edgeStyle", "orthogonalLoop", "jettySize", "curved",
            "exitX", "exitY", "exitDx", "exitDy", "exitPerimeter",
            "entryX", "entryY", "entryDx", "entryDy", "entryPerimeter");

    private final DiagramDraftStore drafts;
    private final ICanvasAnalyzer analyzer;
    private final DiagramDraftVisualReviewPort visualReviews;

    // ObjectProvider keeps focused Spring slices valid when the optional VLM adapter is absent.
    @Autowired
    public DefaultDiagramAgentToolAdapter(
            DiagramDraftStore drafts,
            ObjectProvider<DiagramDraftVisualReviewPort> visualReviews
    ) {
        this(
                drafts,
                new DefaultCanvasAnalyzer(),
                visualReviews.getIfAvailable(() -> DiagramDraftVisualReviewPort.UNAVAILABLE));
    }

    public DefaultDiagramAgentToolAdapter(DiagramDraftStore drafts) {
        this(drafts, new DefaultCanvasAnalyzer(), DiagramDraftVisualReviewPort.UNAVAILABLE);
    }

    public DefaultDiagramAgentToolAdapter(
            DiagramDraftStore drafts,
            ICanvasAnalyzer analyzer,
            DiagramDraftVisualReviewPort visualReviews
    ) {
        this.drafts = drafts;
        this.analyzer = analyzer;
        this.visualReviews = visualReviews;
    }

    @Override
    public DiagramAgentToolResult execute(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            DiagramAgentToolRequest request
    ) {
        if (attempt == null || plan == null || request == null) {
            throw new IllegalArgumentException("tool execution values are required");
        }
        try {
            if (request instanceof CreateDraftRequest create) {
                return create(attempt, plan, create);
            }
            if (request instanceof PatchDraftRequest patch) {
                return patch(attempt, plan, patch);
            }
            if (request instanceof ReviewDraftRequest review) {
                return review(attempt, plan, review);
            }
            return inspect(attempt, plan, (InspectDraftRequest) request);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return DiagramAgentToolResult.rejected(request.toolName(), safeCode(exception));
        }
    }

    private DiagramAgentToolResult create(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            CreateDraftRequest request
    ) {
        if (plan.action() != PlainDrawAction.CREATE) {
            return DiagramAgentToolResult.rejected(request.toolName(), "TOOL_NOT_ALLOWED");
        }
        DiagramDraftSnapshot draft = drafts.create(attempt, request.canvasXml());
        DiagramDraftAnalysis analysis = analyze(draft, plan);
        return DiagramAgentToolResult.success(
                request.toolName(),
                DiagramDraftView.from(draft),
                analysis,
                List.of(),
                List.of(),
                "",
                false);
    }

    private DiagramAgentToolResult patch(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            PatchDraftRequest request
    ) {
        DiagramDraftSnapshot before = drafts.read(attempt, request.draftRef());
        var patched = drafts.patch(
                attempt, request.draftRef(), request.expectedDigest(), request.mutations());
        DiagramDraftSnapshot after = patched.draft();
        if (plan.action() == PlainDrawAction.LAYOUT
                && !sameDiagramSemantics(before.canvasXml(), after.canvasXml(), plan.diagramType())) {
            return DiagramAgentToolResult.rejected(request.toolName(), "LAYOUT_SEMANTIC_CHANGE");
        }
        return DiagramAgentToolResult.success(
                request.toolName(),
                DiagramDraftView.from(after),
                analyze(after, plan),
                patched.changedCellIds(),
                List.of(),
                "",
                false);
    }

    private DiagramAgentToolResult inspect(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            InspectDraftRequest request
    ) {
        DiagramDraftSnapshot draft = drafts.read(attempt, request.draftRef());
        CanvasAnalysis raw = analyzer.analyze(draft.canvasXml(), plan.diagramType());
        List<CanvasCellData> selected = selectCells(raw.getCells(), request);
        boolean includeRawXml = includesRawXml(request.scope());
        int cellLimit = includeRawXml ? 32 : MAX_INSPECTED_CELLS;
        boolean truncated = selected.size() > cellLimit;
        List<InspectedDiagramCell> cells = selected.stream()
                .limit(cellLimit)
                .map(cell -> inspectedCell(cell, includeRawXml))
                .toList();
        String canvasXml = request.scope() == DraftInspectionScope.FULL_XML
                ? draft.canvasXml()
                : "";
        return DiagramAgentToolResult.success(
                request.toolName(),
                DiagramDraftView.from(draft),
                project(raw),
                List.of(),
                cells,
                canvasXml,
                truncated);
    }

    private DiagramAgentToolResult review(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            ReviewDraftRequest request
    ) {
        DiagramDraftSnapshot draft = drafts.read(attempt, request.draftRef());
        if (!draft.digest().equals(request.expectedDigest())) {
            return DiagramAgentToolResult.rejected(
                    request.toolName(), "DRAFT_DIGEST_MISMATCH");
        }
        DiagramDraftAnalysis analysis = analyze(draft, plan);
        return DiagramAgentToolResult.visualReview(
                request.toolName(),
                DiagramDraftView.from(draft),
                analysis,
                visualReviews.review(plan, draft));
    }

    private List<CanvasCellData> selectCells(
            List<CanvasCellData> cells,
            InspectDraftRequest request
    ) {
        List<CanvasCellData> safeCells = cells == null ? List.of() : cells;
        return switch (request.scope()) {
            case SUMMARY, ISSUES_ONLY, FULL_XML -> List.of();
            case TARGET_CELLS -> {
                Set<String> ids = new LinkedHashSet<>(request.cellIds());
                yield safeCells.stream().filter(cell -> ids.contains(cell.getId())).toList();
            }
            case FIND_CELLS -> {
                String query = request.query().toLowerCase(Locale.ROOT);
                yield safeCells.stream().filter(cell -> cell.matches(query)).toList();
            }
            case LAYOUT_GRAPH -> safeCells;
        };
    }

    private boolean includesRawXml(DraftInspectionScope scope) {
        return scope == DraftInspectionScope.TARGET_CELLS
                || scope == DraftInspectionScope.FIND_CELLS;
    }

    private InspectedDiagramCell inspectedCell(CanvasCellData cell, boolean includeRawXml) {
        String rawXml = includeRawXml ? bounded(cell.getRawXml(), MAX_RAW_CELL_XML_CHARS) : "";
        return new InspectedDiagramCell(
                bounded(cell.getId(), 255),
                bounded(cell.getLabel(), 2_000),
                bounded(cell.getKind(), 64),
                bounded(cell.getParentId(), 255),
                bounded(cell.getSource(), 255),
                bounded(cell.getTarget(), 255),
                cell.getX(),
                cell.getY(),
                cell.getWidth(),
                cell.getHeight(),
                rawXml);
    }

    private DiagramDraftAnalysis analyze(DiagramDraftSnapshot draft, PlainDrawPlan plan) {
        return project(analyzer.analyze(draft.canvasXml(), plan.diagramType()));
    }

    private DiagramDraftAnalysis project(CanvasAnalysis analysis) {
        List<CanvasAnalysisIssue> rawIssues =
                analysis.getIssues() == null ? List.of() : analysis.getIssues();
        boolean structurallyValid = rawIssues.stream()
                .noneMatch(issue -> issue.getType() == CanvasIssueType.INVALID_XML);
        List<DiagramDraftIssue> issues = rawIssues.stream()
                .limit(64)
                .map(issue -> new DiagramDraftIssue(
                        issue.getType() == null ? "UNKNOWN" : issue.getType().name(),
                        issue.getSeverity(),
                        issue.getTargetCellIds() == null
                                ? List.of()
                                : issue.getTargetCellIds().stream().limit(16).toList(),
                        bounded(issue.getMessage(), 500)))
                .toList();
        int nodeCount = analysis.getSummary() == null ? 0 : analysis.getSummary().getNodeCount();
        int edgeCount = analysis.getSummary() == null ? 0 : analysis.getSummary().getEdgeCount();
        return new DiagramDraftAnalysis(
                structurallyValid,
                analysis.isValid(),
                nodeCount,
                edgeCount,
                analysis.getSeverity(),
                issues);
    }

    private boolean sameDiagramSemantics(String beforeXml, String afterXml, String diagramType) {
        CanvasAnalysis before = analyzer.analyze(beforeXml, diagramType);
        CanvasAnalysis after = analyzer.analyze(afterXml, diagramType);
        return semanticCells(before.getCells()).equals(semanticCells(after.getCells()));
    }

    private Map<String, SemanticCell> semanticCells(List<CanvasCellData> cells) {
        Map<String, SemanticCell> result = new LinkedHashMap<>();
        for (CanvasCellData cell : cells == null ? List.<CanvasCellData>of() : cells) {
            result.put(cell.getId(), new SemanticCell(
                    text(cell.getKind()),
                    text(cell.getLabel()),
                    text(cell.getParentId()),
                    text(cell.getSource()),
                    text(cell.getTarget()),
                    semanticStyle(cell.getStyle())));
        }
        return result;
    }

    private Map<String, String> semanticStyle(String style) {
        Map<String, String> values = new LinkedHashMap<>();
        Arrays.stream(text(style).split(";"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> {
                    int separator = value.indexOf('=');
                    String key = separator < 0 ? value : value.substring(0, separator);
                    String content = separator < 0 ? "" : value.substring(separator + 1);
                    if (!LAYOUT_STYLE_KEYS.contains(key)) {
                        values.put(key, content);
                    }
                });
        return values;
    }

    private String safeCode(RuntimeException exception) {
        String message = text(exception.getMessage());
        return SAFE_CODE.matcher(message).matches() ? message : "DRAFT_TOOL_REJECTED";
    }

    private String bounded(String value, int limit) {
        String result = text(value);
        return result.length() <= limit ? result : result.substring(0, limit);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private record SemanticCell(
            String kind,
            String label,
            String parent,
            String source,
            String target,
            Map<String, String> style
    ) {
    }
}
