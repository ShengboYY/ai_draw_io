package org.zipp.ai.infrastructure.turn.agent;

import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentToolPort;
import org.zipp.ai.application.turn.agent.DiagramAgentToolRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentToolResult;
import org.zipp.ai.application.turn.agent.DiagramDraftSnapshot;
import org.zipp.ai.application.turn.agent.DiagramDraftStore;
import org.zipp.ai.application.turn.agent.DiagramDraftStructure;
import org.zipp.ai.application.turn.agent.DiagramDraftView;
import org.zipp.ai.application.turn.agent.DraftInspectionScope;
import org.zipp.ai.application.turn.agent.InspectDraftRequest;
import org.zipp.ai.application.turn.agent.InspectedDiagramCell;
import org.zipp.ai.application.turn.agent.PatchDraftRequest;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.service.analysis.DrawioCellDocumentReader;

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
    private final DrawioCellDocumentReader cellReader;

    public DefaultDiagramAgentToolAdapter(DiagramDraftStore drafts) {
        this.drafts = drafts;
        this.cellReader = new DrawioCellDocumentReader();
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
        return DiagramAgentToolResult.success(
                request.toolName(),
                DiagramDraftView.from(draft),
                structure(draft),
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
                && !sameDiagramSemantics(before.canvasXml(), after.canvasXml())) {
            return DiagramAgentToolResult.rejected(request.toolName(), "LAYOUT_SEMANTIC_CHANGE");
        }
        return DiagramAgentToolResult.success(
                request.toolName(),
                DiagramDraftView.from(after),
                structure(after),
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
        List<CanvasCellData> allCells = cellReader.read(draft.canvasXml());
        List<CanvasCellData> selected = selectCells(allCells, request);
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
                structure(allCells),
                List.of(),
                cells,
                canvasXml,
                truncated);
    }

    private List<CanvasCellData> selectCells(
            List<CanvasCellData> cells,
            InspectDraftRequest request
    ) {
        List<CanvasCellData> safeCells = cells == null ? List.of() : cells;
        return switch (request.scope()) {
            case SUMMARY, FULL_XML -> List.of();
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

    private DiagramDraftStructure structure(DiagramDraftSnapshot draft) {
        return structure(cellReader.read(draft.canvasXml()));
    }

    private DiagramDraftStructure structure(List<CanvasCellData> cells) {
        List<CanvasCellData> safeCells = cells == null ? List.of() : cells;
        int nodeCount = (int) safeCells.stream()
                .filter(cell -> "node".equalsIgnoreCase(cell.getKind()))
                .count();
        int edgeCount = (int) safeCells.stream()
                .filter(cell -> "edge".equalsIgnoreCase(cell.getKind()))
                .count();
        return new DiagramDraftStructure(nodeCount, edgeCount, safeCells.size());
    }

    private boolean sameDiagramSemantics(String beforeXml, String afterXml) {
        return semanticCells(cellReader.read(beforeXml))
                .equals(semanticCells(cellReader.read(afterXml)));
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
