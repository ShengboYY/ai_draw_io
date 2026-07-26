package org.zipp.ai.application.turn;

/** Selects one sticky engine for an authenticated cohort during V2 canary mode. */
@FunctionalInterface
public interface TurnEngineCohortSelector {

    SelectedTurnEngine select(String stableCohortKey);
}
