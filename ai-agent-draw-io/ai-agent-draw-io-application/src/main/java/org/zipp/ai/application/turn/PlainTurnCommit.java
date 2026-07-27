package org.zipp.ai.application.turn;

/** All state needed by one fenced Plain canvas/message/terminal transaction. */
public record PlainTurnCommit(
        FencedAttempt attempt,
        PlainDrawAction action,
        String diagramId,
        long expectedCanvasVersion,
        String expectedCanvasContextDigest,
        String canvasXml,
        String assistantMessage,
        String payloadRef
) {

    public PlainTurnCommit {
        if (attempt == null || action == null) {
            throw new IllegalArgumentException("attempt and action must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
        if (expectedCanvasVersion < 0) {
            throw new IllegalArgumentException("expectedCanvasVersion must not be negative");
        }
        expectedCanvasContextDigest = expectedCanvasContextDigest == null
                ? "" : expectedCanvasContextDigest.trim();
        if (expectedCanvasVersion == 0 && !expectedCanvasContextDigest.isEmpty()) {
            throw new IllegalArgumentException("absent canvas cannot carry a context digest");
        }
        if (expectedCanvasVersion > 0 && !hexDigest(expectedCanvasContextDigest)) {
            throw new IllegalArgumentException("pinned canvas requires a SHA-256 context digest");
        }
        ContractValues.requiredText(canvasXml, "canvasXml");
        ContractValues.requiredText(assistantMessage, "assistantMessage");
        ContractValues.requiredText(payloadRef, "payloadRef");
    }

    private static boolean hexDigest(String value) {
        return value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
