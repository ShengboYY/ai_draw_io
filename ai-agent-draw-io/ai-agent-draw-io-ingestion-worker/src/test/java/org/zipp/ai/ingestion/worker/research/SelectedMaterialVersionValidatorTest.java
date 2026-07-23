package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void resolvesExplicitAndAutomaticTaskScopesWithoutBroadeningSelection() {
        List<String> mounted = List.of(
                "drawio-agent-architecture:v1", "drawio-workflow-handbook:v1");

        assertEquals(List.of("drawio-agent-architecture:v1"),
                SelectedMaterialVersionValidator.resolveAllowedSources(
                        "selected_only", "drawio-agent-architecture:v1", mounted));
        assertEquals(mounted, SelectedMaterialVersionValidator.resolveAllowedSources(
                "chartbook_auto", "", mounted));
        assertThrows(IllegalArgumentException.class,
                () -> SelectedMaterialVersionValidator.resolveAllowedSources(
                        "chartbook_auto", "drawio-agent-architecture:v1", mounted));
    }
}
