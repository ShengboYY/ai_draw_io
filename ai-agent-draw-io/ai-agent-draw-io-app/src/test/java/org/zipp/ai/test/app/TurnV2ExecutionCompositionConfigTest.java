package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TurnV2ExecutionCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnV2ExecutionCompositionConfig.class)
            .withBean(ContextAssemblyCoordinator.class, () -> mock(ContextAssemblyCoordinator.class))
            .withBean(TurnDecisionCoordinator.class, () -> mock(TurnDecisionCoordinator.class));

    @Test
    void composesThePreHandlerCoordinatorOnlyWhenBothPreparationSeamsExist() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(TurnV2PreHandlerCoordinator.class));
    }

    @Test
    void composesPlainExecutionOnlyWhenBothPlainPortsExist() {
        contextRunner
                .withBean(PlainGenerationPort.class, () -> mock(PlainGenerationPort.class))
                .withBean(PlainTurnCommitPort.class, () -> mock(PlainTurnCommitPort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(PlainDrawingHandler.class)
                        .hasSingleBean(TurnV2ExecutionCoordinator.class)
                        .hasSingleBean(TurnV2TurnExecutor.class));
    }
}
