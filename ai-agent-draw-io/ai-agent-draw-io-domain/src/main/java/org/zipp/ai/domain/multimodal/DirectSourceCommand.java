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
                                  ResolvedSourceSet resolvedSources) {
    public DirectSourceCommand {
        owner = Objects.requireNonNull(owner, "owner");
        requestId = required(requestId, "requestId");
        runId = required(runId, "runId");
        diagramId = required(diagramId, "diagramId");
        conversationId = required(conversationId, "conversationId");
        attachmentUploadId = required(attachmentUploadId, "attachmentUploadId");
        selectedVersionIds = ids(selectedVersionIds);
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        question = required(question, "question");
        if (question.length() > 2_000) throw new IllegalArgumentException("question is too long");
        confirmationSourceVersionId =
                confirmationSourceVersionId == null ? "" : confirmationSourceVersionId.trim();
        clarifications = List.copyOf(clarifications == null ? List.of() : clarifications.stream()
                .filter(Objects::nonNull).distinct().limit(5).toList());
    }

    /** Compatibility constructor for callers without a confirmation response. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question,
                               List<DirectClarification> clarifications,
                               ResolvedSourceSet resolvedSources) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, "", clarifications, resolvedSources);
    }

    /** Compatibility constructor for callers without a confirmation response. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question,
                               ResolvedSourceSet resolvedSources) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, "", List.of(), resolvedSources);
    }

    /** Compatibility constructor for callers that have not frozen the request source snapshot. */
    public DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                               String diagramId, String conversationId,
                               String attachmentUploadId, List<String> selectedVersionIds,
                               SourceMode sourceMode, String question) {
        this(owner, requestId, runId, diagramId, conversationId, attachmentUploadId,
                selectedVersionIds, sourceMode, question, "", List.of(), null);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    // Match request-source declaration canonicalization for compatibility callers that still resolve here.
    private static List<String> ids(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }
}
