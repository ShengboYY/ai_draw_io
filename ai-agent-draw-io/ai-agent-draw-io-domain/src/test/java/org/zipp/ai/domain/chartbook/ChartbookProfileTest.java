package org.zipp.ai.domain.chartbook;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfilePatch;
import org.zipp.ai.domain.chartbook.model.valobj.DiagramStyleDefaults;
import org.zipp.ai.domain.chartbook.model.valobj.ProfileState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChartbookProfileTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void patchProducesTheNextVersionAndConfiguredState() {
        ChartbookProfile next = ChartbookProfile.empty("book-1", NOW).apply(
                new ChartbookProfilePatch("Use blue lanes", "Ship M1", null,
                        Map.of("owner", "platform"),
                        new DiagramStyleDefaults(Map.of("layout", "elk")),
                        List.of("Keep source-free path deterministic")),
                NOW.plusSeconds(1));

        assertEquals(1, next.version());
        assertEquals(ProfileState.CONFIGURED, next.profileState());
        assertEquals("platform", next.glossary().get("owner"));
        assertEquals(List.of("Keep source-free path deterministic"), next.stableConstraints());
    }

    @Test
    void emptyPatchAndOversizedConstraintAreRejected() {
        ChartbookProfile empty = ChartbookProfile.empty("book-1", NOW);
        assertThrows(IllegalArgumentException.class,
                () -> empty.apply(new ChartbookProfilePatch(null, null, null, null, null, null), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new ChartbookProfile("book-1", 0, "", "", "", Map.of(),
                        DiagramStyleDefaults.empty(), List.of("x".repeat(513)),
                        ProfileState.CONFIGURED, NOW));
    }
}
