package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Raw client declarations plus trusted request identity; content bytes are deliberately absent. */
public record RequestSourceResolutionCommand(CatalogOwner owner, String diagramId, String conversationId,
                                             String runId, SourceMode sourceMode,
                                             List<String> attachmentUploadIds,
                                             List<String> selectedVersionIds) {
    public RequestSourceResolutionCommand {
        Objects.requireNonNull(owner, "owner");
        diagramId = text(diagramId);
        conversationId = text(conversationId);
        runId = required(runId, "runId");
        sourceMode = sourceMode == null ? SourceMode.AUTO : sourceMode;
        attachmentUploadIds = ids(attachmentUploadIds);
        selectedVersionIds = ids(selectedVersionIds);
        if (attachmentUploadIds.size() > 50) throw new IllegalArgumentException("attachment source limit exceeded");
        if (selectedVersionIds.size() > 500) throw new IllegalArgumentException("explicit source limit exceeded");
        if ((!attachmentUploadIds.isEmpty() || !selectedVersionIds.isEmpty())
                && sourceMode == SourceMode.NONE) sourceMode = SourceMode.EXPLICIT;
    }

    public String declarationFingerprint() {
        String canonical = owner.ownerType().name() + "\n" + owner.ownerKey() + "\n"
                + diagramId + "\n" + conversationId + "\n" + sourceMode.name() + "\n"
                + attachmentUploadIds.stream().sorted().reduce("", (left, right) -> left + "\nA:" + right)
                + selectedVersionIds.stream().sorted().reduce("", (left, right) -> left + "\nV:" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<String> ids(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
