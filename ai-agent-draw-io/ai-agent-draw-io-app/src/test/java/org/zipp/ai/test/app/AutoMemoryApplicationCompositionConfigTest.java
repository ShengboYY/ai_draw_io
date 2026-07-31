package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.zipp.ai.application.memory.AutoMemoryExtractionPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorker;
import org.zipp.ai.application.memory.AutoMemoryManagementService;
import org.zipp.ai.application.memory.AutoMemoryManagementStorePort;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryObservationStorePort;
import org.zipp.ai.config.AutoMemoryApplicationCompositionConfig;
import org.zipp.ai.config.AutoMemoryExtractionJob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AutoMemoryApplicationCompositionConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    AutoMemoryApplicationCompositionConfig.class,
                    PortConfiguration.class);

    @Test
    void disabledConfigurationKeepsOnlyTheNoOpCommitPort() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(AutoMemoryExtractionWorkPort.class)
                .doesNotHaveBean(AutoMemoryObservationService.class)
                .doesNotHaveBean(AutoMemoryManagementService.class)
                .doesNotHaveBean(AutoMemoryExtractionWorker.class));
    }

    @Test
    void enabledConfigurationComposesObservationManagementAndWorker() {
        contextRunner.withPropertyValues("app.memory.auto-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryObservationService.class)
                        .hasSingleBean(AutoMemoryManagementService.class)
                        .hasSingleBean(AutoMemoryExtractionWorker.class)
                        .hasSingleBean(AutoMemoryExtractionJob.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PortConfiguration {
        @Bean
        AutoMemoryObservationStorePort observationStore() {
            return mock(AutoMemoryObservationStorePort.class);
        }

        @Bean
        AutoMemoryManagementStorePort managementStore() {
            return mock(AutoMemoryManagementStorePort.class);
        }

        @Bean
        AutoMemoryExtractionPort extractor() {
            return input -> java.util.List.of();
        }
    }
}
