package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.PlainDrawingHandler;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainTurnCommitPort;
import org.zipp.ai.application.turn.TerminalOnlyTurnCommitPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptExecutionStatePort;
import org.zipp.ai.application.turn.TurnAttemptCancellationRegistry;
import org.zipp.ai.application.turn.TurnAttemptInputRecoveryPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnWriteGate;
import org.zipp.ai.application.turn.checkpoint.TurnDecisionCoordinator;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2ExecutionCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2PreHandlerCoordinator;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptRecoveryCoordinator;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TurnV2ExecutionCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnV2ExecutionCompositionConfig.class)
            .withBean(ContextAssemblyCoordinator.class, () -> mock(ContextAssemblyCoordinator.class))
            .withBean(TurnDecisionCoordinator.class, () -> mock(TurnDecisionCoordinator.class))
            .withBean(TurnAttemptCancellationRegistry.class, TurnAttemptCancellationRegistry::new)
            .withBean(TurnAttemptInputRecoveryPort.class, () -> mock(TurnAttemptInputRecoveryPort.class))
            .withBean(TurnControlFacade.class, () -> mock(TurnControlFacade.class))
            .withBean(ThreadPoolExecutor.class, () -> mock(ThreadPoolExecutor.class));

    @Test
    void composesThePreHandlerCoordinatorOnlyWhenBothPreparationSeamsExist() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(TurnAttemptExecutionStatePort.class)
                .hasSingleBean(TurnV2PreHandlerCoordinator.class));
    }

    @Test
    void composesPlainExecutionOnlyWhenBothPlainPortsExist() {
        contextRunner
                .withBean(PlainGenerationPort.class, () -> mock(PlainGenerationPort.class))
                .withBean(PlainTurnCommitPort.class, () -> mock(PlainTurnCommitPort.class))
                .withBean(TerminalOnlyTurnCommitPort.class, () -> mock(TerminalOnlyTurnCommitPort.class))
                .withBean(TurnAttemptLeasePort.class, () -> mock(TurnAttemptLeasePort.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(PlainDrawingHandler.class)
                        .hasSingleBean(TurnWriteGate.class)
                        .hasSingleBean(TurnV2ExecutionCoordinator.class)
                        .hasSingleBean(TurnV2TurnExecutor.class)
                        .hasSingleBean(TurnAttemptLeaseSupervisor.class)
                        .hasSingleBean(ScheduledExecutorService.class)
                        .hasSingleBean(TurnAttemptExecutionRunner.class)
                        .hasSingleBean(TurnAttemptRecoveryCoordinator.class)
                        .satisfies(appContext -> assertThat(appContext.getBean(ScheduledExecutorService.class))
                                .isNotInstanceOf(ThreadPoolExecutor.class)));
    }
}
