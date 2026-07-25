package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class DefaultTurnDeliveryExecutorTest {

    @Test
    void syncAndStreamAdaptersCanShareOneFacadeBoundary() {
        DiagramTurnFacade facade = (actor, command, events) -> new TurnSubmission.NotReady("PAUSED");
        TurnDeliveryExecutor executor = new DefaultTurnDeliveryExecutor(facade);
        UserTurnCommand command = new UserTurnCommand(
                "turn-1", "default", "diagram-1", "client-1", "draw", "runtime-1",
                TurnDeclarations.empty());

        TurnSubmission result = executor.execute(
                new AuthenticatedActor("owner-1", "cohort-1"), command, event -> { });

        assertSame(TurnSubmission.NotReady.class, result.getClass());
    }
}
