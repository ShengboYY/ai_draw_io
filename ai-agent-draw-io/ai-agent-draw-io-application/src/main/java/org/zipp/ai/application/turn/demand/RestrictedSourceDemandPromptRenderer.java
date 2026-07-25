package org.zipp.ai.application.turn.demand;

/** Renders only the restricted demand contract; no conversation, Profile, Memory, or source body. */
public final class RestrictedSourceDemandPromptRenderer {

    public String render(RestrictedSourceDemandInput input) {
        if (input == null) {
            throw new IllegalArgumentException("restricted demand input must not be null");
        }
        StringBuilder prompt = new StringBuilder(1_200);
        append(prompt, "CURRENT_INSTRUCTION_DATA", input.instruction().value());
        append(prompt, "CURRENT_MESSAGE_ATTACHMENT_REFS_DATA",
                input.currentMessageAttachments().stream().map(value -> value.value()).toList().toString());
        append(prompt, "CURRENT_MESSAGE_ATTACHMENT_BINDING_DIGEST_DATA",
                input.attachmentBindingDigest());
        append(prompt, "CHARTBOOK_MEMBERSHIP_ID_DATA",
                input.chartbookMembership().orElse("NONE"));
        append(prompt, "ACTIVE_CLARIFICATION_LABELS_DATA",
                input.activeClarificationLabels().toString());
        prompt.append("\nReturn one JSON object only with demandKind, confidence, safeReason, "
                + "attachmentRefs, relevanceQuery, and spans.");
        return prompt.toString();
    }

    private void append(StringBuilder target, String name, String value) {
        String normalized = value == null ? "" : value.replace("\u0000", " ");
        target.append('[').append(name).append(" length=").append(normalized.length())
                .append("]\n").append(normalized).append("\n");
    }
}
