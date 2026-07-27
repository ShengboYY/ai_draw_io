package org.zipp.ai.domain.material.model.valobj;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Exact, content-free identity set shown before a user confirms permanent deletion. */
public record MaterialDeletionImpact(String materialId, long lifecycleGeneration,
                                     List<String> versionIds, List<String> pinnedSourceIds,
                                     List<String> diagramIds, List<String> chartbookIds,
                                     List<String> citationIds) {
    public MaterialDeletionImpact {
        if (materialId == null || materialId.isBlank() || lifecycleGeneration < 0) {
            throw new IllegalArgumentException("deletion impact is invalid");
        }
        versionIds = normalized(versionIds);
        pinnedSourceIds = normalized(pinnedSourceIds);
        diagramIds = normalized(diagramIds);
        chartbookIds = normalized(chartbookIds);
        citationIds = normalized(citationIds);
    }

    public long versionCount() { return versionIds.size(); }
    public long diagramCount() { return diagramIds.size(); }
    public long chartbookCount() { return chartbookIds.size(); }
    public long citationCount() { return citationIds.size(); }

    public String fingerprint() {
        StringBuilder facts = new StringBuilder(materialId).append(':').append(lifecycleGeneration);
        append(facts, "versions", versionIds);
        append(facts, "pins", pinnedSourceIds);
        append(facts, "diagrams", diagramIds);
        append(facts, "chartbooks", chartbookIds);
        append(facts, "citations", citationIds);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static List<String> normalized(List<String> values) {
        if (values == null) throw new IllegalArgumentException("deletion impact identities are required");
        return values.stream().map(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("deletion impact identity is required");
            }
            return value.trim();
        }).distinct().sorted().toList();
    }

    private static void append(StringBuilder target, String kind, List<String> values) {
        target.append('|').append(kind).append('=').append(values.size());
        values.forEach(value -> target.append(':').append(value.length()).append(':').append(value));
    }
}
