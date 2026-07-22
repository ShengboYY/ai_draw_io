package org.zipp.ai.ingestion.worker.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic evidence deduplication followed by a soft source cap in the result head. */
final class ResearchSourceDiversifier {
    static final String FINGERPRINT =
            "source-diversity-v1:evidence-dedup-v1:top10-max4-per-source:rank-fill";

    private ResearchSourceDiversifier() { }

    static List<String> diversify(List<Candidate> candidates, int limit, int headLimit,
                                  int perSourceHeadCap) {
        Map<String, Candidate> candidateById = new LinkedHashMap<>();
        candidates.forEach(candidate -> candidateById.put(candidate.id(), candidate));
        List<String> deduplicatedIds = ResearchEvidenceDeduplicator.deduplicate(candidates.stream()
                .map(candidate -> new ResearchEvidenceDeduplicator.Candidate(
                        candidate.id(), candidate.citable(), candidate.textSha256(), candidate.evidenceIds()))
                .toList(), candidates.size());
        List<Candidate> deduplicated = deduplicatedIds.stream().map(candidateById::get).toList();
        List<Candidate> selected = new ArrayList<>();
        List<Candidate> deferred = new ArrayList<>();
        Map<String, Integer> sourceCounts = new HashMap<>();
        int targetHeadSize = Math.min(Math.min(headLimit, limit), deduplicated.size());
        for (Candidate candidate : deduplicated) {
            if (selected.size() >= targetHeadSize) break;
            int sourceCount = sourceCounts.getOrDefault(candidate.sourceVersion(), 0);
            if (sourceCount < perSourceHeadCap) {
                selected.add(candidate);
                sourceCounts.put(candidate.sourceVersion(), sourceCount + 1);
            } else {
                deferred.add(candidate);
            }
        }
        // A soft cap must not shrink the head when mounted sources provide too few alternatives.
        for (Candidate candidate : deferred) {
            if (selected.size() >= targetHeadSize) break;
            selected.add(candidate);
        }
        Set<String> selectedIds = selected.stream().map(Candidate::id)
                .collect(java.util.stream.Collectors.toSet());
        for (Candidate candidate : deduplicated) {
            if (selected.size() >= limit) break;
            if (selectedIds.add(candidate.id())) selected.add(candidate);
        }
        return selected.stream().map(Candidate::id).toList();
    }

    record Candidate(String id, String sourceVersion, boolean citable, String textSha256,
                     Set<String> evidenceIds) {
        Candidate {
            if (id == null || id.isBlank() || sourceVersion == null || sourceVersion.isBlank()
                    || textSha256 == null || textSha256.isBlank()) {
                throw new IllegalArgumentException("source-diversity candidate identity is required");
            }
            evidenceIds = Set.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
        }
    }
}
