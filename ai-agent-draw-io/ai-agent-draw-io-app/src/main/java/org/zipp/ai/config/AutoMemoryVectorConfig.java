package org.zipp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.memory.AutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorker;
import org.zipp.ai.application.memory.AutoMemoryVectorShadowTelemetry;
import org.zipp.ai.application.memory.AutoMemoryVectorStorePort;
import org.zipp.ai.application.memory.ScopedAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.ShadowAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;
import org.zipp.ai.infrastructure.adapter.telemetry.AutoMemoryVectorShadowMetrics;

import java.time.Clock;

/** Opt-in Memory vector projection and shadow retrieval; neither can change SQL candidates. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"app.memory.auto-enabled", "app.memory.vector.projection-enabled"},
        havingValue = "true")
public class AutoMemoryVectorConfig {

    @Bean
    @ConditionalOnMissingBean(AutoMemoryVectorStorePort.class)
    public AutoMemoryVectorStorePort autoMemoryVectorStore(
            @Value("${app.memory.vector.pinecone.api-key}") String apiKey,
            @Value("${app.memory.vector.pinecone.index-host}") String indexHost,
            @Value("${app.memory.vector.pinecone.embedding-model}") String model,
            @Value("${app.memory.vector.pinecone.dimension:1024}") int dimension,
            @Value("${app.memory.vector.pinecone.namespace:auto-memory-v1}") String namespace,
            @Value("${app.memory.vector.pinecone.partition-secret}") String partitionSecret,
            ObjectMapper objectMapper
    ) {
        PineconeVectorClient client = new PineconeVectorClient(
                apiKey,
                indexHost,
                model,
                dimension,
                objectMapper,
                PineconeAutoMemoryVectorStoreAdapter.METADATA_FIELDS);
        return new PineconeAutoMemoryVectorStoreAdapter(client, namespace, partitionSecret);
    }

    @Bean
    public AutoMemoryVectorProjectionWorker autoMemoryVectorProjectionWorker(
            AutoMemoryVectorProjectionWorkPort work,
            AutoMemoryVectorStorePort vectors,
            ObjectProvider<Clock> clocks
    ) {
        return new AutoMemoryVectorProjectionWorker(
                work, vectors, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    public AutoMemoryVectorProjectionJob autoMemoryVectorProjectionJob(
            AutoMemoryVectorProjectionWorker worker
    ) {
        return new AutoMemoryVectorProjectionJob(worker);
    }

    @Bean
    @ConditionalOnMissingBean(AutoMemoryVectorShadowTelemetry.class)
    public AutoMemoryVectorShadowTelemetry autoMemoryVectorShadowTelemetry(
            MeterRegistry registry
    ) {
        return new AutoMemoryVectorShadowMetrics(registry);
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.vector.shadow-enabled", havingValue = "true")
    @ConditionalOnMissingBean(AutoMemoryConsolidationCandidateRetriever.class)
    public AutoMemoryConsolidationCandidateRetriever shadowAutoMemoryCandidateRetriever(
            AutoMemoryQueryPort memories,
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorShadowTelemetry telemetry
    ) {
        return new ShadowAutoMemoryConsolidationCandidateRetriever(
                new ScopedAutoMemoryConsolidationCandidateRetriever(memories),
                vectors,
                telemetry);
    }
}
