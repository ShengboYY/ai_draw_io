package org.zipp.ai.ingestion.worker.research;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic candidate deduplication over source-backed text and Evidence identities. */
final class ResearchEvidenceDeduplicator {
    static final String FINGERPRINT = "evidence-dedup-v1:text-sha-or-evidence-jaccard-0.8:citable-first";

    private ResearchEvidenceDeduplicator() { }

    static List<String> deduplicate(List<Candidate> candidates, int limit) {
        List<Candidate> retained = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int duplicateIndex = duplicateIndex(retained, candidate);
            if (duplicateIndex >= 0) {
                Candidate existing = retained.get(duplicateIndex);
                if (candidate.citable() && !existing.citable()) retained.set(duplicateIndex, candidate);
            } else if (retained.size() < limit) {
                retained.add(candidate);
            }
        }
        return retained.stream().map(Candidate::id).toList();
    }

    private static int duplicateIndex(List<Candidate> retained, Candidate candidate) {
        for (int index = 0; index < retained.size(); index++) {
            Candidate existing = retained.get(index);
            if (existing.textSha256().equals(candidate.textSha256())
                    || evidenceJaccard(existing.evidenceIds(), candidate.evidenceIds()) >= 0.80) {
                return index;
            }
        }
        return -1;
    }

    private static double evidenceJaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }

    record Candidate(String id, boolean citable, String textSha256, Set<String> evidenceIds) {
        Candidate {
            if (id == null || id.isBlank() || textSha256 == null || textSha256.isBlank()) {
                throw new IllegalArgumentException("dedup candidate identity is required");
            }
            evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        }
    }
}
