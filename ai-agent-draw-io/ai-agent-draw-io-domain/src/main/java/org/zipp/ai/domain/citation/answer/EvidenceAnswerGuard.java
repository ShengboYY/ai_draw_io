package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.citation.model.valobj.ClaimSupportVerdict;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceSupportRole;

import java.text.Normalizer;
import java.util.*;

/** Fail-closed policy gate between untrusted answer JSON and visible/persisted claims. */
public final class EvidenceAnswerGuard {
    private static final int MAX_CLAIMS = 8;
    private final ClaimSupportVerifierPort verifier;

    public EvidenceAnswerGuard(ClaimSupportVerifierPort verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public Result validate(AnswerProposal proposal, EvidenceAccessContext access) {
        Objects.requireNonNull(access, "access");
        LinkedHashSet<String> errors = new LinkedHashSet<>();
        if (proposal == null) return Result.rejected(List.of("ANSWER_PROPOSAL_MISSING"));
        if (proposal.claims().isEmpty()) errors.add("ANSWER_HAS_NO_SUPPORTED_CLAIM");
        if (proposal.claims().size() > MAX_CLAIMS) errors.add("ANSWER_CLAIM_LIMIT_EXCEEDED");
        Set<String> claimKeys = new HashSet<>();
        List<ClaimSupportVerifierPort.Request> semantic = new ArrayList<>();
        for (AnswerClaim claim : proposal.claims()) {
            validateClaim(claim, access, claimKeys, semantic, errors);
        }
        validateConflicts(proposal.conflicts(), access, errors);
        if (!errors.isEmpty()) return Result.rejected(List.copyOf(errors));
        Map<String, ClaimSupportVerdict> verdicts = verifySemanticClaims(semantic, errors);
        if (!errors.isEmpty()) return Result.rejected(List.copyOf(errors));
        Set<String> semanticKeys = semantic.stream().map(ClaimSupportVerifierPort.Request::statementKey)
                .collect(java.util.stream.Collectors.toSet());
        List<AnswerClaim> supported = proposal.claims().stream()
                .filter(claim -> !semanticKeys.contains(claim.claimKey())
                        || verdicts.get(claim.claimKey()) == ClaimSupportVerdict.ENTAILED)
                .toList();
        List<AnswerGap> gaps = new ArrayList<>(proposal.gaps());
        for (ClaimSupportVerifierPort.Request request : semantic) {
            ClaimSupportVerdict verdict = verdicts.get(request.statementKey());
            if (verdict != ClaimSupportVerdict.ENTAILED) {
                gaps.add(new AnswerGap("guard-" + request.statementKey(), request.statementKey(),
                        verdict == null ? "VERIFIER_UNAVAILABLE" : verdict.name()));
            }
        }
        if (supported.isEmpty()) return Result.rejected(List.of("CLAIM_NOT_ENTAILED"));
        return new Result(true, supported, gaps, proposal.conflicts(), List.of());
    }

    private void validateClaim(AnswerClaim claim, EvidenceAccessContext access, Set<String> claimKeys,
                               List<ClaimSupportVerifierPort.Request> semantic, Set<String> errors) {
        if (claim == null || blank(claim.claimKey()) || blank(claim.statementText()) || claim.supportType() == null) {
            errors.add("ANSWER_CLAIM_INVALID");
            return;
        }
        if (!claimKeys.add(claim.claimKey())) errors.add("DUPLICATE_CLAIM_KEY");
        if (claim.supportType() == AnswerSupportType.AI_KNOWLEDGE) {
            if (!access.aiKnowledgeAllowed()) errors.add("AI_KNOWLEDGE_FORBIDDEN");
            if (!claim.citationKeys().isEmpty() || !claim.supportAtoms().isEmpty()) {
                errors.add("AI_KNOWLEDGE_HAS_CITATION");
            }
            return;
        }
        if (claim.supportType() == AnswerSupportType.VISUAL_VERIFIED) {
            // WP7 currently has no authenticated visual-observation artifact. Reject the label
            // instead of allowing ordinary extracted text to masquerade as pixel verification.
            errors.add("VISUAL_VERIFICATION_UNAVAILABLE");
        }
        if (claim.citationKeys().isEmpty() || claim.citationKeys().size() > 4) {
            errors.add("INVALID_CITATION_COUNT");
        }
        Set<String> uniqueCitations = new HashSet<>();
        for (String key : claim.citationKeys()) if (blank(key) || !uniqueCitations.add(key)) {
            errors.add("INVALID_CITATION_KEY");
        }
        Map<String, List<String>> anchors = new LinkedHashMap<>();
        Set<String> atomKeys = new HashSet<>();
        for (SupportAtom atom : claim.supportAtoms()) {
            if (atom == null || !atomKeys.add(atom.atomKey())) {
                errors.add("DUPLICATE_SUPPORT_ATOM_KEY");
                continue;
            }
            if (!claim.citationKeys().contains(atom.citationKey())) {
                errors.add("SUPPORT_ATOM_KEY_NOT_BOUND");
                continue;
            }
            EvidenceBundleItem item = access.item(atom.citationKey()).orElse(null);
            if (item == null) {
                errors.add("CITATION_KEY_OUTSIDE_BUNDLE");
            } else if (item.supportRole() != EvidenceSupportRole.SUPPORT) {
                errors.add("CONTEXT_ONLY_CITATION_FORBIDDEN");
            } else if (!normalize(item.text()).contains(normalize(atom.anchorText()))) {
                errors.add("SUPPORT_ATOM_NOT_CONTIGUOUS");
            }
            anchors.computeIfAbsent(atom.citationKey(), ignored -> new ArrayList<>()).add(atom.anchorText());
        }
        for (String key : claim.citationKeys()) {
            EvidenceBundleItem item = access.item(key).orElse(null);
            if (item == null) errors.add("CITATION_KEY_OUTSIDE_BUNDLE");
            else if (item.supportRole() != EvidenceSupportRole.SUPPORT) errors.add("CONTEXT_ONLY_CITATION_FORBIDDEN");
            if (!anchors.containsKey(key)) errors.add("CITATION_KEY_WITHOUT_SUPPORT_ATOM");
        }
        List<String> flattened = anchors.values().stream().flatMap(Collection::stream).toList();
        String statement = normalize(claim.statementText());
        // Every persisted DIRECT source must independently contain the exact statement. Requiring
        // only one matching source would let an untrusted model attach unrelated citations.
        boolean directExact = claim.supportType() == AnswerSupportType.DIRECT && !anchors.isEmpty()
                && anchors.keySet().containsAll(claim.citationKeys())
                && claim.citationKeys().stream().allMatch(key -> anchors.getOrDefault(key, List.of())
                .stream().map(this::normalize).anyMatch(statement::equals));
        if (claim.supportType() == AnswerSupportType.DIRECT && !directExact) {
            // DIRECT is an extractive contract, not a semantic-verifier escape hatch. A
            // synthesized claim must label itself as such so every persisted source is verified.
            errors.add("DIRECT_CITATION_NOT_EXACT");
            return;
        }
        if (!flattened.isEmpty() && !directExact) {
            semantic.add(new ClaimSupportVerifierPort.Request(access.runId(), claim.claimKey(),
                    claim.statementText(), flattened));
        }
    }

    private void validateConflicts(List<AnswerConflict> conflicts, EvidenceAccessContext access,
                                   Set<String> errors) {
        for (AnswerConflict conflict : conflicts) {
            if (conflict == null || blank(conflict.conflictKey()) || conflict.citationGroups().size() < 2) {
                errors.add("ANSWER_CONFLICT_INVALID");
                continue;
            }
            for (List<String> group : conflict.citationGroups()) for (String key : group) {
                EvidenceBundleItem item = access.item(key).orElse(null);
                if (item == null || item.supportRole() != EvidenceSupportRole.SUPPORT) {
                    errors.add("CONFLICT_CITATION_INVALID");
                }
            }
        }
    }

    private Map<String, ClaimSupportVerdict> verifySemanticClaims(List<ClaimSupportVerifierPort.Request> requests,
                                                                   Set<String> errors) {
        if (requests.isEmpty()) return Map.of();
        if (requests.size() > MAX_CLAIMS) {
            errors.add("CLAIM_SUPPORT_VERIFIER_LIMIT_EXCEEDED");
            return Map.of();
        }
        try {
            Map<String, ClaimSupportVerdict> verdicts = new HashMap<>();
            for (ClaimSupportVerifierPort.Result result : verifier.verify(List.copyOf(requests))) {
                if (result != null && result.statementKey() != null) {
                    verdicts.put(result.statementKey(), result.verdict());
                }
            }
            if (requests.stream().anyMatch(request -> !verdicts.containsKey(request.statementKey()))) {
                errors.add("CLAIM_SUPPORT_VERIFIER_UNAVAILABLE");
            }
            return Map.copyOf(verdicts);
        } catch (RuntimeException unavailable) {
            errors.add("CLAIM_SUPPORT_VERIFIER_UNAVAILABLE");
            return Map.of();
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replaceAll("<[^>]+>", " ").replaceAll("[\\p{Punct}\\s]+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record Result(boolean accepted, List<AnswerClaim> claims, List<AnswerGap> gaps,
                         List<AnswerConflict> conflicts, List<String> errors) {
        public Result {
            claims = List.copyOf(claims == null ? List.of() : claims);
            gaps = List.copyOf(gaps == null ? List.of() : gaps);
            conflicts = List.copyOf(conflicts == null ? List.of() : conflicts);
            errors = List.copyOf(errors == null ? List.of() : errors);
        }

        static Result rejected(List<String> errors) {
            return new Result(false, List.of(), List.of(), List.of(), errors);
        }
    }
}
