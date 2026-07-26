package org.zipp.ai.trigger.http.turn;

/** HTTP reference for a durable turn; owner identity always comes from authentication. */
public record TurnHttpControlRequest(
        String turnId,
        String conversationReference,
        String diagramId
) {

    public TurnHttpControlRequest {
        required(turnId, "turnId");
        required(conversationReference, "conversationReference");
        required(diagramId, "diagramId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
