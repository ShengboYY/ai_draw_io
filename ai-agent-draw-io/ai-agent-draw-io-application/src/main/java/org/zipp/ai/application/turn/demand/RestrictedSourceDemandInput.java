package org.zipp.ai.application.turn.demand;

import org.zipp.ai.application.turn.OpaqueConversationFileRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Physical input boundary for demand interpretation. It deliberately excludes conversation,
 * profile, memory, source availability, file body, retrieval, and citation data.
 */
public record RestrictedSourceDemandInput(
        CurrentInstruction instruction,
        List<OpaqueConversationFileRef> currentMessageAttachments,
        Optional<String> chartbookMembership,
        Set<String> activeClarificationLabels,
        String attachmentBindingDigest
) {

    /** Compatibility constructor for callers that only have opaque attachment refs. */
    public RestrictedSourceDemandInput(
            CurrentInstruction instruction,
            List<OpaqueConversationFileRef> currentMessageAttachments,
            Optional<String> chartbookMembership,
            Set<String> activeClarificationLabels
    ) {
        this(instruction, currentMessageAttachments, chartbookMembership, activeClarificationLabels,
                digestOf(currentMessageAttachments));
    }

    public RestrictedSourceDemandInput {
        if (instruction == null || chartbookMembership == null || activeClarificationLabels == null
                || attachmentBindingDigest == null || attachmentBindingDigest.isBlank()) {
            throw new IllegalArgumentException("restricted demand input values must not be null");
        }
        currentMessageAttachments = List.copyOf(
                currentMessageAttachments == null ? List.of() : currentMessageAttachments);
        chartbookMembership = chartbookMembership.map(String::trim).filter(value -> !value.isBlank());
        activeClarificationLabels = Set.copyOf(activeClarificationLabels);
    }

    private static String digestOf(List<OpaqueConversationFileRef> attachments) {
        String canonical = (attachments == null ? List.<OpaqueConversationFileRef>of() : attachments)
                .stream().map(value -> value == null ? "<null>" : value.value()).toList().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
