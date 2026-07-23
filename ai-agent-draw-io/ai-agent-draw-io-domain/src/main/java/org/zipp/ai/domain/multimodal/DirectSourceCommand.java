package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;
import java.util.Objects;

/** Untrusted attachment declaration plus trusted request identity for one direct reconstruction. */
public record DirectSourceCommand(CatalogOwner owner, String requestId, String runId,
                                  String diagramId, String conversationId,
                                  String attachmentUploadId, List<String> selectedVersionIds,
                                  SourceMode sourceMode,
                                  String question) {
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
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    // Match request-source declaration canonicalization so the second resolution has the same fingerprint.
    private static List<String> ids(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }
}
