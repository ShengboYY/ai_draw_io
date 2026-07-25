package org.zipp.ai.application.turn;

public record ReplyToClarification(ClarificationId clarificationId)
        implements ClarificationReplyDeclaration {

    public ReplyToClarification {
        if (clarificationId == null) {
            throw new IllegalArgumentException("clarificationId must not be null");
        }
    }
}
