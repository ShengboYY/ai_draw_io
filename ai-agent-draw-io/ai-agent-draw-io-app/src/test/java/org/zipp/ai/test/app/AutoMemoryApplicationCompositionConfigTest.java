package org.zipp.ai.test.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.zipp.ai.application.memory.AutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.AutoMemoryExtractionPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorker;
import org.zipp.ai.application.memory.AutoMemoryManagementService;
import org.zipp.ai.application.memory.AutoMemoryManagementStorePort;
import org.zipp.ai.application.memory.AutoMemoryMaintenancePort;
import org.zipp.ai.application.memory.AutoMemoryMaintenanceService;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryObservationStorePort;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryVectorCandidateHydrationPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorkPort;
import org.zipp.ai.application.memory.CanaryAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.ShadowAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.config.AutoMemoryApplicationCompositionConfig;
import org.zipp.ai.config.AutoMemoryExtractionJob;
import org.zipp.ai.config.AutoMemoryMaintenanceJob;
import org.zipp.ai.config.AutoMemoryVectorConfig;
import org.zipp.ai.application.turn.context.AutoMemoryContextHydrationPort;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelector;

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
                .doesNotHaveBean(AutoMemoryMaintenanceService.class)
                .doesNotHaveBean(AutoMemoryContextSelector.class)
                .doesNotHaveBean(AutoMemoryConsolidationCandidateRetriever.class)
                .doesNotHaveBean(AutoMemoryExtractionWorker.class));
    }

    @Test
    void enabledConfigurationComposesObservationManagementAndWorker() {
        contextRunner.withPropertyValues("app.memory.auto-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryObservationService.class)
                        .hasSingleBean(AutoMemoryManagementService.class)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .hasSingleBean(AutoMemoryContextSelector.class)
                        .hasSingleBean(AutoMemoryExtractionWorker.class)
                        .hasSingleBean(AutoMemoryExtractionJob.class)
                        .doesNotHaveBean(AutoMemoryMaintenanceService.class));
    }

    @Test
    void customCandidateRetrieverReplacesTheSqlDefault() {
        AutoMemoryConsolidationCandidateRetriever custom = query -> java.util.List.of();

        contextRunner
                .withBean(AutoMemoryConsolidationCandidateRetriever.class, () -> custom)
                .withPropertyValues("app.memory.auto-enabled=true")
                .run(context -> assertThat(context)
                        .getBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .isSameAs(custom));
    }

    @Test
    void shadowFlagWithoutProjectionKeepsTheSqlRetriever() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.shadow-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .hasSingleBean(AutoMemoryExtractionWorker.class));
    }

    @Test
    void canaryFlagWithoutProjectionKeepsTheSqlRetriever() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.canary-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .hasSingleBean(AutoMemoryExtractionWorker.class));
    }

    @Test
    void semanticContextFlagWithoutProjectionKeepsTheSqlSelector() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.context.semantic-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryContextSelector.class));
    }

    @Test
    void projectionAndShadowReplaceOnlyTheSqlCandidateReader() {
        contextRunner.withUserConfiguration(AutoMemoryVectorConfig.class)
                .withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true",
                        "app.memory.vector.shadow-enabled=true",
                        "app.memory.vector.pinecone.api-key=test-key",
                        "app.memory.vector.pinecone.index-host=https://memory-index.example",
                        "app.memory.vector.pinecone.embedding-model=multilingual-e5-large",
                        "app.memory.vector.pinecone.dimension=3",
                        "app.memory.vector.pinecone.partition-secret=test-secret")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .getBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .isInstanceOf(ShadowAutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void projectionAndCanaryReplaceOnlyTheSqlCandidateReader() {
        contextRunner.withUserConfiguration(AutoMemoryVectorConfig.class)
                .withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true",
                        "app.memory.vector.canary-enabled=true",
                        "app.memory.vector.pinecone.api-key=test-key",
                        "app.memory.vector.pinecone.index-host=https://memory-index.example",
                        "app.memory.vector.pinecone.embedding-model=multilingual-e5-large",
                        "app.memory.vector.pinecone.dimension=3",
                        "app.memory.vector.pinecone.partition-secret=test-secret")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .getBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .isInstanceOf(CanaryAutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void agingRequiresBothAutoMemoryAndItsIndependentOptIn() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.aging-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryMaintenanceService.class)
                        .hasSingleBean(AutoMemoryMaintenanceJob.class));
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
        AutoMemoryMaintenancePort maintenancePort() {
            return mock(AutoMemoryMaintenancePort.class);
        }

        @Bean
        AutoMemoryQueryPort memoryQuery() {
            return mock(AutoMemoryQueryPort.class);
        }

        @Bean
        AutoMemoryVectorCandidateHydrationPort vectorHydration() {
            return mock(AutoMemoryVectorCandidateHydrationPort.class);
        }

        @Bean
        AutoMemoryVectorProjectionWorkPort vectorProjectionWork() {
            return mock(AutoMemoryVectorProjectionWorkPort.class);
        }

        @Bean
        AutoMemoryContextHydrationPort memoryContextHydration() {
            return mock(AutoMemoryContextHydrationPort.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AutoMemoryExtractionPort extractor() {
            return input -> java.util.List.of();
        }
    }
}
