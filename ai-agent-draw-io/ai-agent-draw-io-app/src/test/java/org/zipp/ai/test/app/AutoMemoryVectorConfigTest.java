package org.zipp.ai.test.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.zipp.ai.application.memory.AutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryVectorCandidateHydrationPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorker;
import org.zipp.ai.application.memory.AutoMemoryVectorShadowTelemetry;
import org.zipp.ai.application.memory.AutoMemoryVectorStorePort;
import org.zipp.ai.application.memory.CanaryAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.ShadowAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.config.AutoMemoryVectorConfig;
import org.zipp.ai.config.AutoMemoryVectorProjectionJob;
import org.zipp.ai.application.turn.context.AutoMemoryContextHydrationPort;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AutoMemoryVectorConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AutoMemoryVectorConfig.class, PortConfiguration.class)
            .withPropertyValues(
                    "app.memory.vector.pinecone.api-key=test-key",
                    "app.memory.vector.pinecone.index-host=https://memory-index.example",
                    "app.memory.vector.pinecone.embedding-model=multilingual-e5-large",
                    "app.memory.vector.pinecone.dimension=3",
                    "app.memory.vector.pinecone.partition-secret=test-secret");

    @Test
    void projectionRemainsAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(AutoMemoryVectorProjectionWorker.class)
                .doesNotHaveBean(AutoMemoryVectorProjectionJob.class)
                .doesNotHaveBean(AutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void projectionOptInComposesWorkerWithoutChangingCandidateRetrieval() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryVectorProjectionWorker.class)
                        .hasSingleBean(AutoMemoryVectorProjectionJob.class)
                        .hasSingleBean(AutoMemoryVectorStorePort.class)
                        .hasSingleBean(AutoMemoryVectorShadowTelemetry.class)
                        .doesNotHaveBean(AutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void shadowOptInAddsSqlPreservingDecorator() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true",
                        "app.memory.vector.shadow-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .getBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .isInstanceOf(ShadowAutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void canaryOptInAddsVectorFirstSqlFallbackRetriever() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true",
                        "app.memory.vector.canary-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .getBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .isInstanceOf(CanaryAutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void canaryTakesPrecedenceWhenBothRetrievalFlagsAreSet() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true",
                        "app.memory.vector.shadow-enabled=true",
                        "app.memory.vector.canary-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .getBean(AutoMemoryConsolidationCandidateRetriever.class)
                        .isInstanceOf(CanaryAutoMemoryConsolidationCandidateRetriever.class));
    }

    @Test
    void semanticContextOptInUsesTheSharedVectorStore() {
        contextRunner.withPropertyValues(
                        "app.memory.auto-enabled=true",
                        "app.memory.vector.projection-enabled=true",
                        "app.memory.context.semantic-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(AutoMemoryContextSelector.class)
                        .hasSingleBean(AutoMemoryVectorStorePort.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PortConfiguration {
        @Bean
        AutoMemoryVectorProjectionWorkPort vectorWork() {
            return mock(AutoMemoryVectorProjectionWorkPort.class);
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
    }
}
