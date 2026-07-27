package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointLoadOutcome;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointQueryPort;
import org.zipp.ai.application.turn.context.ContextReadSetCommitPort;
import org.zipp.ai.application.turn.context.ContextReadSetLoadOutcome;
import org.zipp.ai.application.turn.context.ContextReadSetQueryPort;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCheckpointCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnCheckpointCompositionConfig.class)
            .withPropertyValues("turn-engine.execution.enabled=true")
            .withBean(ContextReadSetQueryPort.class, () -> ignored -> new ContextReadSetLoadOutcome.Missing())
            .withBean(ContextReadSetCommitPort.class, () -> (ignored, proposal) ->
                    new org.zipp.ai.application.turn.context.ContextReadSetOutcome.Retry())
            .withBean(TurnDecisionCheckpointQueryPort.class, () ->
                    ignored -> new TurnDecisionCheckpointLoadOutcome.Missing())
            .withBean(TurnDecisionCheckpointCommitPort.class, () -> (ignored, proposal) ->
                    new org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointOutcome.Retry());

    @Test
    void composesLoadFirstCoordinatorsOnlyWhenAllPersistenceSeamsExist() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(
                    org.zipp.ai.application.turn.context.DefaultContextReadSetCoordinator.class);
            assertThat(context).hasSingleBean(
                    org.zipp.ai.application.turn.checkpoint.DefaultTurnDecisionCheckpointCoordinator.class);
        });
    }
}
