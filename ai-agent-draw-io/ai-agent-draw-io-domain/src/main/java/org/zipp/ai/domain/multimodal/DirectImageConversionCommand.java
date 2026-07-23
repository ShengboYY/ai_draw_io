package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;

import java.util.Objects;

/** Authorized source plus optimistic canvas identity for one direct conversion commit. */
public record DirectImageConversionCommand(
        DirectSourceCommand source,
        String userId,
        String diagramId,
        String currentXml,
        DiagramType diagramType,
        Long expectedVersion,
        String expectedContentHash
) {
    public DirectImageConversionCommand {
        source = Objects.requireNonNull(source, "source");
        userId = required(userId, "userId");
        diagramId = required(diagramId, "diagramId");
        currentXml = currentXml == null ? "" : currentXml;
        diagramType = diagramType == null ? DiagramType.GENERIC : diagramType;
        expectedContentHash = optional(expectedContentHash);
        // A prepared material owner cannot mutate another owner's canvas.
        if (!source.owner().ownerKey().equals(userId)) {
            throw new IllegalArgumentException("source owner must match canvas user");
        }
    }

    private static String required(String value, String field) {
        String normalized = optional(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
