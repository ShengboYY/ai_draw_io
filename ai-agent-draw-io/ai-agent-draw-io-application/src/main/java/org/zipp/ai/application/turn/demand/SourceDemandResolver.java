package org.zipp.ai.application.turn.demand;

// This resolver is deliberately deterministic and has no source-aware dependencies.

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * No-I/O resolver for untrusted demand proposals. Authorization, Probe, snapshot, and artifact
 * reads remain outside this class.
 */
public final class SourceDemandResolver {

    public SourceDemandResolution resolve(
            SourceDemandProposal proposal,
            RestrictedSourceDemandInput input,
            DemandResolutionPolicy policy
    ) {
        if (proposal == null || input == null || policy == null) {
            return new SourceDemandUnavailable("SOURCE_DEMAND_INPUT_INVALID", Duration.ZERO);
        }
        ProposalEvidence evidence = evidenceOf(proposal);
        if (!evidenceMatchesInstruction(evidence, input.instruction())) {
            return new SourceDemandUnavailable("SOURCE_DEMAND_EVIDENCE_INVALID", Duration.ZERO);
        }
        if (!input.inputDigest().equals(evidence.inputDigest())) {
            return new SourceDemandUnavailable("SOURCE_DEMAND_INPUT_DIGEST_INVALID", Duration.ZERO);
        }
        if (!policy.modelVersion().equals(evidence.modelVersion())
                || !policy.policyVersion().equals(evidence.policyVersion())) {
            return new SourceDemandUnavailable("SOURCE_DEMAND_POLICY_VERSION_INVALID", Duration.ZERO);
        }

        List<DemandResolutionReason> verified = List.of(
                new DemandResolutionReason(2, DemandResolutionCode.CURRENT_INSTRUCTION_EVIDENCE_VERIFIED));
        if (proposal instanceof NoSourceDemandProposal noSource) {
            if (noSource.evidence().relevanceQuery().isPresent()) {
                return new SourceDemandUnavailable("SOURCE_DEMAND_REFERENT_INVALID", Duration.ZERO);
            }
            return new ResolvedSourceDemand(
                    new NoSourceDemand(),
                    append(verified, new DemandResolutionReason(9, DemandResolutionCode.PLAIN_ONLY_ACTION)));
        }
        if (proposal instanceof AmbiguousSourceDemandProposal) {
            return clarification("AMBIGUOUS_SOURCE_DEMAND", DemandResolutionCode.CLARIFICATION_REQUIRED);
        }

        TypedSourceDemandProposal typed = (TypedSourceDemandProposal) proposal;
        if (!typed.evidence().relevanceQuery().equals(
                java.util.Optional.ofNullable(typed.relevanceQuery()))) {
            return new SourceDemandUnavailable("SOURCE_DEMAND_REFERENT_INVALID", Duration.ZERO);
        }
        if (usesCurrentMessageAttachments(typed.kind())) {
            if (!validAttachmentQuery(typed)) {
                return new SourceDemandUnavailable("SOURCE_DEMAND_REFERENT_INVALID", Duration.ZERO);
            }
            if (typed.attachmentRefs().isEmpty()
                    || !input.eligibleAttachmentRefs().stream()
                    .map(value -> value.value())
                    .toList()
                    .containsAll(typed.attachmentRefs())) {
                return clarification("ATTACHMENT_REFERENT", DemandResolutionCode.ATTACHMENT_NOT_BOUND_TO_MESSAGE);
            }
            if (isComposite(typed.kind()) && input.chartbookMembership().isEmpty()) {
                if (typed.kind()
                        == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED) {
                    return clarification(
                            "COMPOSITE_RETRIEVAL_SCOPE",
                            DemandResolutionCode.CLARIFICATION_REQUIRED);
                }
            }
            return new ResolvedSourceDemand(
                    new AcceptedSourceDemand(
                            typed.kind(), typed.attachmentRefs(), typed.relevanceQuery()),
                    append(verified,
                            new DemandResolutionReason(3, DemandResolutionCode.ATTACHMENT_BINDING_VERIFIED),
                            new DemandResolutionReason(4, DemandResolutionCode.REQUIRED_PROPOSAL_ACCEPTED)));
        }

        if (!typed.attachmentRefs().isEmpty()) {
            return new SourceDemandUnavailable("SOURCE_DEMAND_REFERENT_INVALID", Duration.ZERO);
        }
        if (typed.relevanceQuery() == null || typed.relevanceQuery().isBlank()) {
            return clarification("RELEVANCE_QUERY", DemandResolutionCode.CLARIFICATION_REQUIRED);
        }
        if (input.chartbookMembership().isEmpty()) {
            if (!policy.plainFallbackSigned()) {
                return new SourceDemandUnavailable("OPTIONAL_FALLBACK_NOT_SIGNED", Duration.ZERO);
            }
            return new ResolvedSourceDemand(
                    new NoSourceDemand(),
                    append(verified,
                            new DemandResolutionReason(5, DemandResolutionCode.NO_PROJECT_SOURCE_SCOPE),
                            new DemandResolutionReason(5, DemandResolutionCode.OPTIONAL_FALLBACK_SIGNED)));
        }
        return new ResolvedSourceDemand(
                new AcceptedSourceDemand(typed.kind(), List.of(), typed.relevanceQuery()),
                append(verified,
                        new DemandResolutionReason(5, DemandResolutionCode.OPTIONAL_DISCOVERY_ACCEPTED)));
    }

    private boolean usesCurrentMessageAttachments(SourceDemandKind kind) {
        return kind != SourceDemandKind.OPTIONAL_DISCOVERY;
    }

    private boolean isComposite(SourceDemandKind kind) {
        return kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL
                || kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED;
    }

    private boolean validAttachmentQuery(TypedSourceDemandProposal proposal) {
        // Direct reads consume the bound file as-is; every retrieval branch needs a query.
        if (proposal.kind() == SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED
                || proposal.kind() == SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED) {
            return proposal.relevanceQuery() == null;
        }
        return proposal.relevanceQuery() != null && !proposal.relevanceQuery().isBlank();
    }

    private ProposalEvidence evidenceOf(SourceDemandProposal proposal) {
        if (proposal instanceof NoSourceDemandProposal value) {
            return value.evidence();
        }
        if (proposal instanceof TypedSourceDemandProposal value) {
            return value.evidence();
        }
        return ((AmbiguousSourceDemandProposal) proposal).evidence();
    }

    private boolean evidenceMatchesInstruction(ProposalEvidence evidence, CurrentInstruction instruction) {
        if (evidence.spans().isEmpty()) {
            return false;
        }
        for (CurrentInstructionSpan span : evidence.spans()) {
            if (span.endExclusive() > instruction.value().length()
                    || !span.digest().equals(instruction.spanDigest(span.startInclusive(), span.endExclusive()))) {
                return false;
            }
        }
        return true;
    }

    private SourceDemandResolution clarification(String kind, DemandResolutionCode code) {
        return new NeedsSourceClarification(
                kind,
                List.of(new DemandResolutionReason(4, code)));
    }

    private List<DemandResolutionReason> append(
            List<DemandResolutionReason> current,
            DemandResolutionReason... extra
    ) {
        List<DemandResolutionReason> result = new ArrayList<>(current);
        result.addAll(List.of(extra));
        return List.copyOf(result);
    }
}
