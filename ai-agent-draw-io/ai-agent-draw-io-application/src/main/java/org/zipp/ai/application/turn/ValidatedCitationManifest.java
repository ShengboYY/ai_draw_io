package org.zipp.ai.application.turn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Citation facts already checked against the prepared Evidence whitelist and support guard. */
public final class ValidatedCitationManifest {

    private final String manifestDigest;
    private final List<Citation> citations;

    private ValidatedCitationManifest(String manifestDigest, List<Citation> citations) {
        SourceCommitBindingDigest.required(manifestDigest, "manifestDigest");
        this.manifestDigest = manifestDigest;
        this.citations = List.copyOf(citations);
        if (this.citations.isEmpty()) {
            throw new IllegalArgumentException("validated citations must not be empty");
        }
        Set<String> citationIds = new HashSet<>();
        for (Citation citation : this.citations) {
            if (!citationIds.add(citation.citationId())) {
                throw new IllegalArgumentException("duplicate citation id");
            }
        }
    }

    /**
     * Issues a manifest only when every link belongs to the prepared whitelist and the caller's
     * support verifier has marked every factual target as supported.
     */
    public static ValidationOutcome validate(
            String manifestDigest,
            Set<String> allowedEvidenceIds,
            List<CandidateCitation> candidates
    ) {
        Set<String> allowed = Set.copyOf(allowedEvidenceIds == null ? Set.of() : allowedEvidenceIds);
        List<CandidateCitation> proposed = List.copyOf(candidates == null ? List.of() : candidates);
        if (proposed.isEmpty()) {
            return new ValidationOutcome.Rejected("EVIDENCE_INSUFFICIENT");
        }
        for (CandidateCitation citation : proposed) {
            if (!citation.supportVerified()) {
                return new ValidationOutcome.Rejected("CLAIM_SUPPORT_NOT_VERIFIED");
            }
            if (citation.links().isEmpty()
                    || citation.links().stream().anyMatch(link ->
                    !allowed.contains(link.evidenceId()))) {
                return new ValidationOutcome.Rejected("CITATION_OUTSIDE_EVIDENCE_WHITELIST");
            }
        }
        List<Citation> ready = proposed.stream()
                .map(candidate -> new Citation(
                        candidate.citationId(),
                        candidate.targetKey(),
                        candidate.semanticHash(),
                        candidate.links()))
                .toList();
        return new ValidationOutcome.Ready(
                new ValidatedCitationManifest(manifestDigest, ready));
    }

    public String manifestDigest() {
        return manifestDigest;
    }

    public List<Citation> citations() {
        return citations;
    }

    public record CandidateCitation(
            String citationId,
            String targetKey,
            String semanticHash,
            boolean supportVerified,
            List<EvidenceLink> links
    ) {
        public CandidateCitation {
            ContractValues.requiredText(citationId, "citationId");
            ContractValues.requiredText(targetKey, "targetKey");
            SourceCommitBindingDigest.required(semanticHash, "semanticHash");
            links = List.copyOf(links == null ? List.of() : links);
        }
    }

    public record Citation(
            String citationId,
            String targetKey,
            String semanticHash,
            List<EvidenceLink> links
    ) {
        public Citation {
            ContractValues.requiredText(citationId, "citationId");
            ContractValues.requiredText(targetKey, "targetKey");
            SourceCommitBindingDigest.required(semanticHash, "semanticHash");
            links = List.copyOf(links == null ? List.of() : links);
            if (links.isEmpty()) {
                throw new IllegalArgumentException("citation links must not be empty");
            }
        }
    }

    public record EvidenceLink(
            String citationKey,
            String evidenceId,
            String materialId,
            String versionId,
            String revisionId,
            String origin
    ) {
        public EvidenceLink {
            ContractValues.requiredText(citationKey, "citationKey");
            ContractValues.requiredText(evidenceId, "evidenceId");
            ContractValues.requiredText(materialId, "materialId");
            ContractValues.requiredText(versionId, "versionId");
            ContractValues.requiredText(revisionId, "revisionId");
            ContractValues.requiredText(origin, "origin");
        }
    }

    public sealed interface ValidationOutcome
            permits ValidationOutcome.Ready, ValidationOutcome.Rejected {

        record Ready(ValidatedCitationManifest manifest) implements ValidationOutcome {
            public Ready {
                if (manifest == null) {
                    throw new IllegalArgumentException("manifest must not be null");
                }
            }
        }

        record Rejected(String code) implements ValidationOutcome {
            public Rejected {
                ContractValues.requiredText(code, "code");
            }
        }
    }
}
