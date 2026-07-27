package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.OpaqueConversationFileRef;

/** Opaque attachment metadata; it deliberately has no body, OCR, or retrieval content. */
public record CurrentMessageAttachmentView(
        OpaqueConversationFileRef reference,
        String mediaType,
        String displayName
) {

    public CurrentMessageAttachmentView {
        if (reference == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        ContextValues.requiredText(mediaType, "mediaType");
        ContextValues.requiredText(displayName, "displayName");
        if (mediaType.length() > 128 || displayName.length() > 256) {
            throw new IllegalArgumentException("attachment metadata exceeds the bounded limit");
        }
    }
}
