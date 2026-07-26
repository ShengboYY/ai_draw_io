package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StableTurnEngineCohortSelectorTest {

    @Test
    void allowlistedCohortIsV2EvenWhenPercentageRolloutIsZero() {
        TurnEngineCohortSelector selector = new StableTurnEngineCohortSelector(
                "release-salt-1", 0, Set.of("org-allowlisted"));

        assertEquals(SelectedTurnEngine.V2, selector.select("org-allowlisted"));
        assertEquals(SelectedTurnEngine.LEGACY, selector.select("org-other"));
    }

    @Test
    void sameCohortAndSaltAlwaysProduceTheSameAssignment() {
        TurnEngineCohortSelector selector = new StableTurnEngineCohortSelector(
                "release-salt-1", 37, Set.of());

        assertEquals(selector.select("org-stable"), selector.select("org-stable"));
    }

    @Test
    void rolloutConfigurationIsBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new StableTurnEngineCohortSelector("salt", -1, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StableTurnEngineCohortSelector("salt", 101, Set.of()));
    }
}
