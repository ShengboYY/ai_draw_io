package org.zipp.ai.application.turn.agent;

import java.util.List;

/** Stable tool result contract; failures contain codes rather than exception text or XML. */
public record DiagramAgentToolResult(
        String toolName,
        boolean success,
        String outcomeCode,
        DiagramDraftView draft,
        DiagramDraftAnalysis analysis,
        List<String> changedCellIds,
        List<InspectedDiagramCell> cells,
        String canvasXml,
        boolean truncated,
        DiagramDraftVisualReview visualReview
) {

    public DiagramAgentToolResult {
        toolName = toolName == null ? "" : toolName.trim();
        outcomeCode = outcomeCode == null ? "" : outcomeCode.trim();
        changedCellIds = List.copyOf(changedCellIds == null ? List.of() : changedCellIds);
        cells = List.copyOf(cells == null ? List.of() : cells);
        canvasXml = canvasXml == null ? "" : canvasXml.trim();
        if (toolName.isBlank() || outcomeCode.isBlank()) {
            throw new IllegalArgumentException("tool result identity is required");
        }
        if (success && (draft == null || analysis == null)) {
            throw new IllegalArgumentException("successful tool result requires draft and analysis");
        }
    }

    public static DiagramAgentToolResult success(
            String toolName,
            DiagramDraftView draft,
            DiagramDraftAnalysis analysis,
            List<String> changedCellIds,
            List<InspectedDiagramCell> cells,
            String canvasXml,
            boolean truncated
    ) {
        return new DiagramAgentToolResult(
                toolName, true, "SUCCESS", draft, analysis,
                changedCellIds, cells, canvasXml, truncated, null);
    }

    public static DiagramAgentToolResult visualReview(
            String toolName,
            DiagramDraftView draft,
            DiagramDraftAnalysis analysis,
            DiagramDraftVisualReview visualReview
    ) {
        if (visualReview == null) {
            throw new IllegalArgumentException("visual review is required");
        }
        return new DiagramAgentToolResult(
                toolName, true, visualReview.decision(), draft, analysis,
                List.of(), List.of(), "", false, visualReview);
    }

    public static DiagramAgentToolResult rejected(String toolName, String code) {
        return new DiagramAgentToolResult(
                toolName, false, code, null, null,
                List.of(), List.of(), "", false, null);
    }
}
