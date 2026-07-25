package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCheckpointQueryPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.checkpoint.TurnRouteDecisionCodec;
import org.zipp.ai.application.turn.classification.TurnClassificationService;
import org.zipp.ai.application.turn.context.RestrictedSourceDemandInputFactory;
import org.zipp.ai.application.turn.context.SemanticRouterContextProjector;
import org.zipp.ai.application.turn.planning.TurnRouteComputer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TurnPlannerCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnPlannerCompositionConfig.class)
            .withBean(SemanticRouterContextProjector.class, () -> mock(SemanticRouterContextProjector.class))
            .withBean(RestrictedSourceDemandInputFactory.class,
                    () -> mock(RestrictedSourceDemandInputFactory.class))
            .withBean(TurnClassificationService.class, () -> mock(TurnClassificationService.class))
            .withBean(TurnDecisionCheckpointQueryPort.class,
                    () -> mock(TurnDecisionCheckpointQueryPort.class))
            .withBean(TurnDecisionCheckpointCommitPort.class,
                    () -> mock(TurnDecisionCheckpointCommitPort.class))
            .withBean(TurnRouteDecisionCodec.class, () -> mock(TurnRouteDecisionCodec.class));

    @Test
    void composesCheckpointedPlannerOnlyWhenAllSeamsExist() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TurnRouteComputer.class);
            assertThat(context).hasSingleBean(TurnDecisionCoordinator.class);
        });
    }
}
