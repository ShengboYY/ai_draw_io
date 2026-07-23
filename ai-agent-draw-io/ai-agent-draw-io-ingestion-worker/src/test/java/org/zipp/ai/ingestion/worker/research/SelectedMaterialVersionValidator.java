package org.zipp.ai.ingestion.worker.research;

import java.util.Collection;
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
}
