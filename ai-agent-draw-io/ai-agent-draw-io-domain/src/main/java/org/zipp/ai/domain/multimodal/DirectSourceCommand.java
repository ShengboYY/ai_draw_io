package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;

import java.util.List;
import java.util.Objects;

/** Untrusted attachment declaration plus trusted request identity for one direct reconstruction. */
public record DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                                  String diagramId, String conversationId,
                                  String attachmentUploadId, List<String> selectedVersionIds,
                                  SourceMode sourceMode,
                                  String question,
                                  String confirmationSourceVersionId,
                                  List<DirectClarification> clarifications,
                                  ResolvedSourceSet resolvedSources,
                                  String primaryDirectVersionId) {
    public DirectSourceCommand {
        owner = Objects.requireNonNull(owner, "owner");
        requestId = required(requestId, "requestId");
        runId = required(runId, "runId");
        diagramId = required(diagramId, "diagramId");
        conversationId = required(conversationId, "conversationId");
        attachmentUploadId = optional(attachmentUploadId);
        selectedVersionIds = ids(selectedVersionIds);
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        question = required(question, "question");
        if (question.length() > 2_000) throw new IllegalArgumentException("question is too long");
        confirmationSourceVersionId =
                confirmationSourceVersionId == null ? "" : confirmationSourceVersionId.trim();
        clarifications = List.copyOf(clarifications == null ? List.of() : clarifications.stream()
                .filter(Objects::nonNull).distinct().limit(5).toList());
        primaryDirectVersionId = optional(primaryDirectVersionId);
        if (primaryDirectVersionId.isEmpty()) {
            primaryDirectVersionId = singleDirectReadableImageVersion(resolvedSources);
        }
    }

    /** Compatibility constructor for callers whose source snapshot contains one directly readable image. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question,
                               String confirmationSourceVersionId,
                               List<DirectClarification> clarifications,
                               ResolvedSourceSet resolvedSources) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, confirmationSourceVersionId,
                clarifications, resolvedSources, "");
    }

    /** Compatibility constructor for callers without a confirmation response. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question,
                               List<DirectClarification> clarifications,
                               ResolvedSourceSet resolvedSources) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, "", clarifications, resolvedSources, "");
    }

    /** Compatibility constructor for callers without a confirmation response. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question,
                               ResolvedSourceSet resolvedSources) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, "", List.of(), resolvedSources, "");
    }

    /** Compatibility constructor for callers that have not frozen the request source snapshot. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, "", List.of(), null, "");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private static String singleDirectReadableImageVersion(ResolvedSourceSet sources) {
        if (sources == null) return "";
        List<String> candidates = sources.sources().stream()
                .filter(org.zipp.ai.domain.retrieval.ResolvedSource::directReadable)
                .map(org.zipp.ai.domain.retrieval.ResolvedSource::versionId)
                .distinct().toList();
        return candidates.size() == 1 ? candidates.get(0) : "";
    }

    // Match request-source declaration canonicalization for compatibility callers that still resolve here.
    private static List<String> ids(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }
}
