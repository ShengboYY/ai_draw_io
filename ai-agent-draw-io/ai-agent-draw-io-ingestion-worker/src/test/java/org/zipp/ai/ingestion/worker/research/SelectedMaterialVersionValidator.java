package org.zipp.ai.ingestion.worker.research;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Validates the agent-visible material selection before retrieval touches a chartbook. */
final class SelectedMaterialVersionValidator {

    private SelectedMaterialVersionValidator() { }

    static void requireMounted(String selectedMaterialVersion, Collection<String> mountedSourceVersions) {
        String selected = selectedMaterialVersion == null ? "" : selectedMaterialVersion.trim();
        Collection<String> mounted = Objects.requireNonNull(mountedSourceVersions, "mountedSourceVersions");
        if (selected.isEmpty() || !mounted.contains(selected)) {
            throw new IllegalArgumentException("selected material version must be mounted in the chartbook");
        }
    }

    /** Resolves the exact retrieval scope declared by one generation task. */
    static List<String> resolveAllowedSources(String sourceScopeMode, String selectedMaterialVersion,
                                              Collection<String> mountedSourceVersions) {
        Collection<String> mounted = Objects.requireNonNull(mountedSourceVersions, "mountedSourceVersions");
        if ("selected_only".equals(sourceScopeMode)) {
            requireMounted(selectedMaterialVersion, mounted);
            return List.of(selectedMaterialVersion.trim());
        }
        if ("chartbook_auto".equals(sourceScopeMode)) {
            if (selectedMaterialVersion != null && !selectedMaterialVersion.trim().isEmpty()) {
                throw new IllegalArgumentException("automatic chartbook scope must not declare a selected material");
            }
            if (mounted.isEmpty()) {
                throw new IllegalArgumentException("automatic chartbook scope requires mounted material");
            }
            return List.copyOf(mounted);
        }
        throw new IllegalArgumentException("unsupported generation source scope mode");
    }
}
