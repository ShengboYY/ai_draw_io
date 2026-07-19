package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.DocumentStructure;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCandidate;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCandidateSelection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic local budget policy applied before any optional VLM disclosure. */
public final class VisualCandidateSelectionPolicy {

    private final int maximumPages;
    private final double pageFraction;
    private final int maximumRegionsPerPage;

    public VisualCandidateSelectionPolicy(int maximumPages, double pageFraction, int maximumRegionsPerPage) {
        if (maximumPages < 1 || !Double.isFinite(pageFraction) || pageFraction <= 0 || pageFraction > 1
                || maximumRegionsPerPage < 1) {
            throw new IllegalArgumentException("visual selection limits are invalid");
        }
        this.maximumPages = maximumPages;
        this.pageFraction = pageFraction;
        this.maximumRegionsPerPage = maximumRegionsPerPage;
    }

    public String fingerprint() {
        return "visual-selection-v1:max-pages=" + maximumPages + ":page-fraction=" + pageFraction
                + ":max-regions-per-page=" + maximumRegionsPerPage + ":caption-area-rank-v1";
    }

    public VisualCandidateSelection select(DocumentStructure structure, int pageCount) {
        DocumentStructure source = Objects.requireNonNull(structure, "structure");
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
        Map<Integer, List<VisualCandidate>> byPage = new LinkedHashMap<>();
        source.visualCandidates().forEach(candidate -> {
            if (candidate.pageNo() > pageCount) {
                throw new IllegalArgumentException("visual candidate page exceeds the document page count");
            }
            byPage.computeIfAbsent(candidate.pageNo(), ignored -> new ArrayList<>()).add(candidate);
        });
        Comparator<VisualCandidate> candidateRank = Comparator
                .comparing((VisualCandidate candidate) -> candidate.captionBlockId() == null)
                .thenComparing(VisualCandidateSelectionPolicy::area, Comparator.reverseOrder())
                .thenComparing(VisualCandidate::candidateId);
        byPage.values().forEach(candidates -> candidates.sort(candidateRank));

        int pageBudget = Math.min(maximumPages, Math.max(1, (int) Math.ceil(pageCount * pageFraction)));
        List<Map.Entry<Integer, List<VisualCandidate>>> rankedPages = new ArrayList<>(byPage.entrySet());
        rankedPages.sort((left, right) -> {
            int ranked = candidateRank.compare(left.getValue().get(0), right.getValue().get(0));
            return ranked == 0 ? Integer.compare(left.getKey(), right.getKey()) : ranked;
        });
        var selectedPages = new HashSet<Integer>();
        rankedPages.stream().limit(pageBudget).forEach(entry -> selectedPages.add(entry.getKey()));

        List<VisualCandidate> selected = byPage.entrySet().stream()
                .filter(entry -> selectedPages.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream().limit(maximumRegionsPerPage))
                .toList();
        int total = source.visualCandidates().size();
        return new VisualCandidateSelection(selected, selectedPages.size(), total, total - selected.size());
    }

    private static Double area(VisualCandidate candidate) {
        return candidate.regions().stream().mapToDouble(VisualCandidateSelectionPolicy::area).sum();
    }

    private static double area(NormalizedBoundingBox box) {
        return (box.x2() - box.x1()) * (box.y2() - box.y1());
    }
}
