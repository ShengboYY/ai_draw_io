package org.zipp.ai.application.turn.context;

import java.util.List;

public record CurrentMessageAttachmentsContext(
        String bindingDigest,
        List<CurrentMessageAttachmentView> values
) {

    public CurrentMessageAttachmentsContext {
        if (bindingDigest == null || bindingDigest.isBlank()) {
            throw new IllegalArgumentException("bindingDigest must not be blank");
        }
        values = List.copyOf(values == null ? List.of() : values);
        if (values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("attachment values must not contain null");
        }
    }
}
