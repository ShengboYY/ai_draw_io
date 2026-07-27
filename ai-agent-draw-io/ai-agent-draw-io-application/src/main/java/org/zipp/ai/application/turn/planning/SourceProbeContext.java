package org.zipp.ai.application.turn.planning;

/** Server-owned identity needed by a production Probe adapter to resolve the opaque command. */
public record SourceProbeContext(
        String ownerType,
        String ownerKey,
        String diagramId,
        String conversationId,
        String runId
) {

    public SourceProbeContext {
        required(ownerType, "ownerType");
        required(ownerKey, "ownerKey");
        required(diagramId, "diagramId");
        required(conversationId, "conversationId");
        required(runId, "runId");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
