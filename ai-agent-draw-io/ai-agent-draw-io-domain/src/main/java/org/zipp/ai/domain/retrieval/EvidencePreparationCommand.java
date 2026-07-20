package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;
import java.util.Objects;

/** Public evidence command; raw canvas XML and client summaries are deliberately absent. */
public record EvidencePreparationCommand(CatalogOwner owner, String diagramId, String conversationId, String requestId,
                                         String runId, String userMessage, CanvasProbe canvasProbe,
                                         ValidatedSelection selection, SourceMode sourceMode,
                                         List<String> selectedVersionIds, String evidenceNeed,
                                         String targetNeed) {
    public EvidencePreparationCommand {
        Objects.requireNonNull(owner, "owner");
        diagramId = text(diagramId);
        conversationId = text(conversationId);
        requestId = required(requestId, "requestId");
        runId = required(runId, "runId");
        userMessage = text(userMessage);
        canvasProbe = canvasProbe == null ? CanvasProbe.unavailableProbe() : canvasProbe;
        selection = selection == null ? ValidatedSelection.empty() : selection;
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        selectedVersionIds = selectedVersionIds == null ? List.of() : selectedVersionIds.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        evidenceNeed = normalizedNeed(evidenceNeed, "OPTIONAL");
        targetNeed = normalizedNeed(targetNeed, "NONE");
        // Explicit sources are an authoritative server-side signal. A conflicting client mode
        // cannot suppress retrieval and accidentally send the request to the legacy Drawer.
        if (!selectedVersionIds.isEmpty() && sourceMode == SourceMode.NONE) sourceMode = SourceMode.EXPLICIT;
        if (!selectedVersionIds.isEmpty()) evidenceNeed = "REQUIRED";
    }

    public boolean requiresEvidence() {
        return "REQUIRED".equals(evidenceNeed);
    }

    public boolean needsEvidence() {
        return !"NONE".equals(evidenceNeed) && sourceMode != SourceMode.NONE;
    }

    public boolean requiresTarget() {
        return "REQUIRED".equals(targetNeed);
    }

    private static String normalizedNeed(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return List.of("NONE", "OPTIONAL", "REQUIRED").contains(normalized) ? normalized : fallback;
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
