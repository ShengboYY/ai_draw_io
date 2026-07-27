package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.context.ContextCandidateLoadOutcome;
import org.zipp.ai.application.turn.context.ContextCandidateQueryPort;
import org.zipp.ai.application.turn.context.ContextMaterializationOutcome;
import org.zipp.ai.application.turn.context.ContextReadSetCommitPort;
import org.zipp.ai.application.turn.context.ContextReadSetLoadOutcome;
import org.zipp.ai.application.turn.context.ContextReadSetMaterializerPort;
import org.zipp.ai.application.turn.context.ContextReadSetQueryPort;
import org.zipp.ai.application.turn.context.ContextAssemblyCoordinator;
import org.zipp.ai.application.turn.context.ContextReadSetOutcome;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblyCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.ContextAssemblyCompositionConfig.class)
            .withPropertyValues("turn-engine.execution.enabled=true")
            .withBean(ContextReadSetQueryPort.class, () -> ignored -> new ContextReadSetLoadOutcome.Missing())
            .withBean(ContextReadSetCommitPort.class, () -> (ignored, proposal) ->
                    new ContextReadSetOutcome.Retry())
            .withBean(ContextCandidateQueryPort.class, () -> (ignored, command) ->
                    new ContextCandidateLoadOutcome.Retry())
            .withBean(ContextReadSetMaterializerPort.class, () ->
                    (ignored, command, readSet) -> new ContextMaterializationOutcome.Retry());

    @Test
    void composesContextCoordinatorOnlyWhenAllSeamsExist() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(
                    org.zipp.ai.application.turn.context.DefaultBaseTurnContextAssembler.class);
            assertThat(context.getBeansOfType(ContextAssemblyCoordinator.class)).hasSize(1);
        });
    }
}
