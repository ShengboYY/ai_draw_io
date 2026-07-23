package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectedMaterialVersionValidatorTest {

    @Test
    void acceptsOnlyAnExplicitlyMountedSelectedMaterialVersion() {
        assertDoesNotThrow(() -> SelectedMaterialVersionValidator.requireMounted(
                "drawio-agent-architecture:v1", List.of(
                        "drawio-agent-architecture:v1", "drawio-workflow-handbook:v1")));

        assertThrows(IllegalArgumentException.class, () -> SelectedMaterialVersionValidator.requireMounted(
                "drawio-recovery-runbook:v1", List.of("drawio-agent-architecture:v1")));
        assertThrows(IllegalArgumentException.class, () -> SelectedMaterialVersionValidator.requireMounted(
                "", List.of("drawio-agent-architecture:v1")));
    }
}
