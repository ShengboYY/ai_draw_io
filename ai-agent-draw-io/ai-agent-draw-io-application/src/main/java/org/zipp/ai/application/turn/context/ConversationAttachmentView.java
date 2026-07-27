package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.OpaqueConversationFileRef;

/**
 * Metadata for an attachment durably bound to one prior user message.
 *
 * <p>The body is deliberately absent. The existing Source Probe remains responsible for
 * authorization, availability, and reading the selected source.</p>
 */
public record ConversationAttachmentView(
        OpaqueConversationFileRef reference,
        String mediaType,
        String displayName
) {

    public ConversationAttachmentView {
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
