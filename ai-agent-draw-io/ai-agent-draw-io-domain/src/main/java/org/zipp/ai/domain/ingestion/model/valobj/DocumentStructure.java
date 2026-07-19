package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record DocumentStructure(String schemaVersion, List<DocumentSection> sections,
                                List<BoilerplateBlockRef> boilerplateBlocks,
                                List<VisualCandidate> visualCandidates, String structureHash) {
    public DocumentStructure {
        if (schemaVersion == null || schemaVersion.isBlank()
                || structureHash == null || !structureHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("document structure identity is invalid");
        }
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        boilerplateBlocks = List.copyOf(Objects.requireNonNull(boilerplateBlocks, "boilerplateBlocks"));
        visualCandidates = List.copyOf(Objects.requireNonNull(visualCandidates, "visualCandidates"));
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("document structure requires at least one section");
        }
    }
}
