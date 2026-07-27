package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.demand.AttachmentCandidateOrigin;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceAttachmentCandidate;

import java.util.List;
import java.util.Optional;

/** Semantic Router input after server-owned context assembly and before source planning. */
public record SemanticRouterInput(
        CurrentInstruction instruction,
        RouterContextView context,
        List<OpaqueConversationFileRef> eligibleAttachmentRefs,
        List<SourceAttachmentCandidate> attachmentCandidates,
        Optional<String> chartbookMembership,
        String attachmentBindingDigest,
        ModelInputBinding modelInputBinding
) {

    public SemanticRouterInput(CurrentInstruction instruction, RouterContextView context) {
        this(instruction, context, List.of(), List.of(), Optional.empty(), "none",
                ModelInputBinding.unbound());
    }

    public SemanticRouterInput(
            CurrentInstruction instruction,
            RouterContextView context,
            ModelInputBinding modelInputBinding
    ) {
        this(instruction, context, List.of(), List.of(), Optional.empty(), "none",
                modelInputBinding);
    }

    /** Compatibility constructor for projection tests that predate candidate metadata. */
    public SemanticRouterInput(
            CurrentInstruction instruction,
            RouterContextView context,
            List<OpaqueConversationFileRef> currentMessageAttachments,
            Optional<String> chartbookMembership,
            String attachmentBindingDigest,
            ModelInputBinding modelInputBinding
    ) {
        this(instruction, context, currentMessageAttachments,
                legacyCandidates(currentMessageAttachments), chartbookMembership,
                attachmentBindingDigest, modelInputBinding);
    }

    public SemanticRouterInput {
        if (instruction == null || context == null || chartbookMembership == null
                || attachmentBindingDigest == null || attachmentBindingDigest.isBlank()
                || modelInputBinding == null) {
            throw new IllegalArgumentException("semantic router input values must not be null");
        }
        eligibleAttachmentRefs = List.copyOf(
                eligibleAttachmentRefs == null ? List.of() : eligibleAttachmentRefs);
        attachmentCandidates = List.copyOf(
                attachmentCandidates == null ? List.of() : attachmentCandidates);
        if (!eligibleAttachmentRefs.equals(attachmentCandidates.stream()
                .map(SourceAttachmentCandidate::reference).toList())) {
            throw new IllegalArgumentException("attachment candidate metadata does not match refs");
        }
        chartbookMembership = chartbookMembership.map(String::trim)
                .filter(value -> !value.isBlank());
    }

    /** Adds only opaque source metadata; source bodies and availability remain outside the router. */
    public SemanticRouterInput withSourceContext(RestrictedSourceDemandInput source) {
        if (source == null || !instruction.digest().equals(source.instruction().digest())) {
            throw new IllegalArgumentException("semantic router source context is invalid");
        }
        return new SemanticRouterInput(
                instruction,
                context,
                source.eligibleAttachmentRefs(),
                source.attachmentCandidates(),
                source.chartbookMembership(),
                source.attachmentBindingDigest(),
                modelInputBinding);
    }

    public SemanticRouterInput withModelInputBinding(ModelInputBinding binding) {
        return new SemanticRouterInput(
                instruction,
                context,
                eligibleAttachmentRefs,
                attachmentCandidates,
                chartbookMembership,
                attachmentBindingDigest,
                binding);
    }

    /** Digest of the complete router projection, independent of any runtime ADK session. */
    public String inputDigest() {
        return ModelInputBinding.digestOf(
                instruction.digest(),
                context.toString(),
                eligibleAttachmentRefs.stream()
                        .map(OpaqueConversationFileRef::value).toList().toString(),
                attachmentCandidates.toString(),
                chartbookMembership.orElse(""),
                attachmentBindingDigest);
    }

    /** Compatibility alias for callers that still use the old current-message-only terminology. */
    public List<OpaqueConversationFileRef> currentMessageAttachments() {
        return eligibleAttachmentRefs;
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
