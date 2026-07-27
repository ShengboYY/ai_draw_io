package org.zipp.ai.application.turn.demand;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Physical input boundary for demand interpretation. It deliberately excludes conversation text,
 * profile, memory, source availability, file body, retrieval, and citation data.
 */
public record RestrictedSourceDemandInput(
        CurrentInstruction instruction,
        List<OpaqueConversationFileRef> eligibleAttachmentRefs,
        List<SourceAttachmentCandidate> attachmentCandidates,
        Optional<String> chartbookMembership,
        Set<String> activeClarificationLabels,
        String attachmentBindingDigest,
        ModelInputBinding modelInputBinding
) {

    /** Compatibility constructor for callers that only have opaque attachment refs. */
    public RestrictedSourceDemandInput(
            CurrentInstruction instruction,
            List<OpaqueConversationFileRef> currentMessageAttachments,
            Optional<String> chartbookMembership,
            Set<String> activeClarificationLabels
    ) {
        this(instruction, currentMessageAttachments, legacyCandidates(currentMessageAttachments),
                chartbookMembership, activeClarificationLabels,
                digestOf(currentMessageAttachments), ModelInputBinding.unbound());
    }

    public RestrictedSourceDemandInput(
            CurrentInstruction instruction,
            List<OpaqueConversationFileRef> currentMessageAttachments,
            Optional<String> chartbookMembership,
            Set<String> activeClarificationLabels,
            String attachmentBindingDigest
    ) {
        this(instruction, currentMessageAttachments, legacyCandidates(currentMessageAttachments),
                chartbookMembership, activeClarificationLabels,
                attachmentBindingDigest, ModelInputBinding.unbound());
    }

    public RestrictedSourceDemandInput withModelInputBinding(ModelInputBinding binding) {
        return new RestrictedSourceDemandInput(
                instruction, eligibleAttachmentRefs, attachmentCandidates, chartbookMembership,
                activeClarificationLabels, attachmentBindingDigest, binding);
    }

    public RestrictedSourceDemandInput {
        if (instruction == null || chartbookMembership == null || activeClarificationLabels == null
                || attachmentBindingDigest == null || attachmentBindingDigest.isBlank()) {
            throw new IllegalArgumentException("restricted demand input values must not be null");
        }
        if (modelInputBinding == null) {
            throw new IllegalArgumentException("model input binding must not be null");
        }
        eligibleAttachmentRefs = List.copyOf(
                eligibleAttachmentRefs == null ? List.of() : eligibleAttachmentRefs);
        attachmentCandidates = List.copyOf(
                attachmentCandidates == null ? List.of() : attachmentCandidates);
        if (attachmentCandidates.size() > 8
                || attachmentCandidates.stream().anyMatch(value -> value == null)
                || !eligibleAttachmentRefs.equals(attachmentCandidates.stream()
                .map(SourceAttachmentCandidate::reference).toList())) {
            throw new IllegalArgumentException("attachment candidates do not match the effective allow-list");
        }
        chartbookMembership = chartbookMembership.map(String::trim).filter(value -> !value.isBlank());
        activeClarificationLabels = Set.copyOf(activeClarificationLabels);
    }

    /** Digest of every fact visible to the restricted interpreter, in stable order. */
    public String inputDigest() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "instruction", instruction.digest());
        append(canonical, "attachments", eligibleAttachmentRefs.stream()
                .map(OpaqueConversationFileRef::value).toList().toString());
        append(canonical, "attachmentCandidates", attachmentCandidates.toString());
        append(canonical, "membership", chartbookMembership.orElse(""));
        append(canonical, "clarifications", activeClarificationLabels.stream().sorted().toList().toString());
        append(canonical, "attachmentBinding", attachmentBindingDigest);
        return sha256(canonical.toString());
    }

    /** Compatibility alias for callers that still use the old current-message-only terminology. */
    public List<OpaqueConversationFileRef> currentMessageAttachments() {
        return eligibleAttachmentRefs;
    }

    private void append(StringBuilder target, String name, String value) {
        target.append(name).append('=').append(value.length()).append(':').append(value).append('\n');
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private static List<SourceAttachmentCandidate> legacyCandidates(
            List<OpaqueConversationFileRef> attachments
    ) {
        return (attachments == null ? List.<OpaqueConversationFileRef>of() : attachments).stream()
                .map(reference -> new SourceAttachmentCandidate(
                        reference,
                        "application/octet-stream",
                        reference.value(),
                        AttachmentCandidateOrigin.CURRENT_MESSAGE))
                .toList();
    }
}
