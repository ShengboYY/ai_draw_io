package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectedMaterialScopeTest {

    @Test
    void acceptsOnlyAnExplicitlyMountedSelectedMaterialVersion() {
        assertEquals("drawio-agent-architecture:v1", SelectedMaterialScope.requireMounted(
                "drawio-agent-architecture:v1", List.of(
                        "drawio-agent-architecture:v1", "drawio-workflow-handbook:v1")));

        assertThrows(IllegalArgumentException.class, () -> SelectedMaterialScope.requireMounted(
                "drawio-recovery-runbook:v1", List.of("drawio-agent-architecture:v1")));
        assertThrows(IllegalArgumentException.class, () -> SelectedMaterialScope.requireMounted(
                "", List.of("drawio-agent-architecture:v1")));
    }
}
