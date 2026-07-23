package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.List;
import java.util.Objects;

/** Public evidence command; raw canvas XML and client summaries are deliberately absent. */
public record EvidencePreparationCommand(CatalogOwner owner, String diagramId, String conversationId, String requestId,
                                         String runId, String userMessage, CanvasProbe canvasProbe,
                                         ValidatedSelection selection, SourceMode sourceMode,
                                         ResolvedSourceSet resolvedSources,
                                         List<String> selectedVersionIds, String evidenceNeed,
                                         String targetNeed, String clarificationNeed,
                                         boolean diagramReconstructionRequested) {
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
        clarificationNeed = normalizedClarification(clarificationNeed);
        boolean snapshotHasDeclaration = resolvedSources != null
                && (!resolvedSources.declaredVersionIds().isEmpty()
                || resolvedSources.processingSourceCount() > 0
                || resolvedSources.unavailableSourceCount() > 0);
        // The trusted snapshot is authoritative. A contradictory legacy client mode cannot
        // suppress a declared attachment/version or send it to the evidence-free Drawer.
        if (snapshotHasDeclaration) sourceMode = resolvedSources.mode();
        if ((!selectedVersionIds.isEmpty() || snapshotHasDeclaration)
                && sourceMode == SourceMode.NONE) sourceMode = SourceMode.EXPLICIT;
        if (!selectedVersionIds.isEmpty() || snapshotHasDeclaration) evidenceNeed = "REQUIRED";
    }

    /** Compatibility constructor for callers that already supply a trusted source snapshot. */
    public EvidencePreparationCommand(CatalogOwner owner, String diagramId, String conversationId,
                                      String requestId, String runId, String userMessage,
                                      CanvasProbe canvasProbe, ValidatedSelection selection,
                                      SourceMode sourceMode, ResolvedSourceSet resolvedSources,
                                      List<String> selectedVersionIds, String evidenceNeed,
                                      String targetNeed) {
        this(owner, diagramId, conversationId, requestId, runId, userMessage, canvasProbe, selection,
                sourceMode, resolvedSources, selectedVersionIds, evidenceNeed, targetNeed, "NONE", false);
    }

    /** Compatibility constructor for callers that provide a structured clarification requirement. */
    public EvidencePreparationCommand(CatalogOwner owner, String diagramId, String conversationId,
                                      String requestId, String runId, String userMessage,
                                      CanvasProbe canvasProbe, ValidatedSelection selection,
                                      SourceMode sourceMode, ResolvedSourceSet resolvedSources,
                                      List<String> selectedVersionIds, String evidenceNeed,
                                      String targetNeed, String clarificationNeed) {
        this(owner, diagramId, conversationId, requestId, runId, userMessage, canvasProbe, selection,
                sourceMode, resolvedSources, selectedVersionIds, evidenceNeed, targetNeed,
                clarificationNeed, false);
    }

    /** Compatibility constructor for a trusted snapshot that requests image topology reconstruction. */
    public EvidencePreparationCommand(CatalogOwner owner, String diagramId, String conversationId,
                                      String requestId, String runId, String userMessage,
                                      CanvasProbe canvasProbe, ValidatedSelection selection,
                                      SourceMode sourceMode, ResolvedSourceSet resolvedSources,
                                      List<String> selectedVersionIds, String evidenceNeed,
                                      String targetNeed, boolean diagramReconstructionRequested) {
        this(owner, diagramId, conversationId, requestId, runId, userMessage, canvasProbe, selection,
                sourceMode, resolvedSources, selectedVersionIds, evidenceNeed, targetNeed, "NONE",
                diagramReconstructionRequested);
    }

    /** Compatibility constructor for isolated callers that still exercise the catalog seam directly. */
    public EvidencePreparationCommand(CatalogOwner owner, String diagramId, String conversationId,
                                      String requestId, String runId, String userMessage,
                                      CanvasProbe canvasProbe, ValidatedSelection selection,
                                      SourceMode sourceMode, List<String> selectedVersionIds,
                                      String evidenceNeed, String targetNeed) {
        this(owner, diagramId, conversationId, requestId, runId, userMessage, canvasProbe, selection,
                sourceMode, null, selectedVersionIds, evidenceNeed, targetNeed, "NONE", false);
    }

    public boolean hasResolvedSources() {
        return resolvedSources != null;
    }

    public List<String> declaredVersionIds() {
        return resolvedSources == null ? selectedVersionIds : resolvedSources.declaredVersionIds();
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

    public boolean needsSourceClarification() {
        return "SOURCE".equals(clarificationNeed);
    }

    public boolean needsClaimClarification() {
        return "CLAIM".equals(clarificationNeed);
    }

    private static String normalizedNeed(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return List.of("NONE", "OPTIONAL", "REQUIRED").contains(normalized) ? normalized : fallback;
    }

    private static String normalizedClarification(String value) {
        if (value == null || value.isBlank()) return "NONE";
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return List.of("NONE", "SOURCE", "CLAIM").contains(normalized) ? normalized : "NONE";
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
