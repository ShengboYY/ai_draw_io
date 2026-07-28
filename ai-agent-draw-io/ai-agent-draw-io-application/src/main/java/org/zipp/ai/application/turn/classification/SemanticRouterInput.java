package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.OpaqueConversationFileRef;
import org.zipp.ai.application.turn.RequestedDiagramSkill;
import org.zipp.ai.application.turn.demand.AttachmentCandidateOrigin;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.SourceAttachmentCandidate;
import org.zipp.ai.application.turn.skill.DiagramSkillCatalogSnapshot;

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
        ModelInputBinding modelInputBinding,
        DiagramSkillCatalogSnapshot skillCatalog,
        List<RequestedDiagramSkill> requestedDiagramSkills
) {

    public SemanticRouterInput(CurrentInstruction instruction, RouterContextView context) {
        this(instruction, context, List.of(), List.of(), Optional.empty(), "none",
                ModelInputBinding.unbound(), DiagramSkillCatalogSnapshot.empty(), List.of());
    }

    public SemanticRouterInput(
            CurrentInstruction instruction,
            RouterContextView context,
            ModelInputBinding modelInputBinding
    ) {
        this(instruction, context, List.of(), List.of(), Optional.empty(), "none",
                modelInputBinding, DiagramSkillCatalogSnapshot.empty(), List.of());
    }

    /** Compatibility constructor for callers that predate the V2 skill projection. */
    public SemanticRouterInput(
            CurrentInstruction instruction,
            RouterContextView context,
            List<OpaqueConversationFileRef> eligibleAttachmentRefs,
            List<SourceAttachmentCandidate> attachmentCandidates,
            Optional<String> chartbookMembership,
            String attachmentBindingDigest,
            ModelInputBinding modelInputBinding
    ) {
        this(instruction, context, eligibleAttachmentRefs, attachmentCandidates,
                chartbookMembership, attachmentBindingDigest, modelInputBinding,
                DiagramSkillCatalogSnapshot.empty(), List.of());
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
                attachmentBindingDigest, modelInputBinding,
                DiagramSkillCatalogSnapshot.empty(), List.of());
    }

    public SemanticRouterInput {
        if (instruction == null || context == null || chartbookMembership == null
                || attachmentBindingDigest == null || attachmentBindingDigest.isBlank()
                || modelInputBinding == null || skillCatalog == null) {
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
        requestedDiagramSkills = List.copyOf(
                requestedDiagramSkills == null ? List.of() : requestedDiagramSkills);
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
                modelInputBinding,
                skillCatalog,
                requestedDiagramSkills);
    }

    /** Adds one owner-scoped snapshot and caller declarations before model-input binding. */
    public SemanticRouterInput withSkillContext(
            DiagramSkillCatalogSnapshot catalog,
            List<RequestedDiagramSkill> requestedSkills
    ) {
        return new SemanticRouterInput(
                instruction,
                context,
                eligibleAttachmentRefs,
                attachmentCandidates,
                chartbookMembership,
                attachmentBindingDigest,
                modelInputBinding,
                catalog,
                requestedSkills);
    }

    public SemanticRouterInput withModelInputBinding(ModelInputBinding binding) {
        return new SemanticRouterInput(
                instruction,
                context,
                eligibleAttachmentRefs,
                attachmentCandidates,
                chartbookMembership,
                attachmentBindingDigest,
                binding,
                skillCatalog,
                requestedDiagramSkills);
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
                attachmentBindingDigest,
                skillCatalog.digest(),
                requestedDiagramSkills.stream().map(RequestedDiagramSkill::value).toList().toString());
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
