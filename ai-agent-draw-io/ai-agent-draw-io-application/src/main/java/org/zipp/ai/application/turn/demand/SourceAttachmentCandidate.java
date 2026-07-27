package org.zipp.ai.application.turn.demand;

import org.zipp.ai.application.turn.OpaqueConversationFileRef;

/** Bounded attachment metadata visible to classification; source bytes are never included. */
public record SourceAttachmentCandidate(
        OpaqueConversationFileRef reference,
        String mediaType,
        String displayName,
        AttachmentCandidateOrigin origin
) {

    public SourceAttachmentCandidate {
        if (reference == null || origin == null) {
            throw new IllegalArgumentException("attachment candidate identity must not be null");
        }
        mediaType = requiredBounded(mediaType, "mediaType", 128);
        displayName = requiredBounded(displayName, "displayName", 256);
    }

    private static String requiredBounded(String value, String name, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > limit) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
