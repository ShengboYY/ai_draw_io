package org.zipp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.memory.AutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryVectorCandidateHydrationPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryVectorProjectionWorker;
import org.zipp.ai.application.memory.AutoMemoryVectorShadowTelemetry;
import org.zipp.ai.application.memory.AutoMemoryVectorStorePort;
import org.zipp.ai.application.memory.CanaryAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.ScopedAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.ShadowAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.turn.context.AutoMemoryContextHydrationPort;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelector;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanner;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;
import org.zipp.ai.infrastructure.adapter.telemetry.AutoMemoryVectorShadowMetrics;

import java.time.Clock;

/** Opt-in Memory vector projection with shadow and local canary retrieval modes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"app.memory.auto-enabled", "app.memory.vector.projection-enabled"},
        havingValue = "true")
public class AutoMemoryVectorConfig {

    @Bean
    @ConditionalOnProperty(
            name = "app.memory.context.semantic-enabled", havingValue = "true")
    @ConditionalOnMissingBean(AutoMemoryContextSelector.class)
    public AutoMemoryContextSelector semanticAutoMemoryContextSelector(
            AutoMemoryQueryPort memories,
            AutoMemoryContextHydrationPort hydration,
            AutoMemoryVectorStorePort vectors,
            ObjectProvider<AutoMemoryRecallPlanner> recallPlanners,
            @Value("${app.memory.context.minimum-score:0.82}") double minimumScore,
            @Value("${app.memory.context.minimum-lead:0.02}") double minimumLead,
            @Value("${app.memory.context.maximum-score-drop:0.03}") double maximumScoreDrop,
            @Value("${app.memory.context.facet-minimum-score:0.80}") double facetMinimumScore,
            @Value("${app.memory.context.reference-facet-minimum-score:0.76}")
            double referenceFacetMinimumScore,
            @Value("${app.memory.context.facet-minimum-lead:0.01}") double facetMinimumLead,
            @Value("${app.memory.context.max-entries:12}") int maxEntries,
            @Value("${app.memory.context.max-characters:6000}") int maxCharacters
    ) {
        return new AutoMemoryContextSelector(
                memories,
                hydration,
                vectors,
                recallPlanners.getIfAvailable(() -> AutoMemoryRecallPlanner.NONE),
                new AutoMemoryContextSelector.SemanticPolicy(
                        minimumScore, minimumLead, maximumScoreDrop,
                        facetMinimumScore, referenceFacetMinimumScore, facetMinimumLead),
                new AutoMemoryContextSelector.Budget(maxEntries, maxCharacters));
    }

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
    @ConditionalOnExpression(
            "${app.memory.vector.shadow-enabled:false} "
                    + "&& !${app.memory.vector.canary-enabled:false}")
    @ConditionalOnMissingBean(AutoMemoryConsolidationCandidateRetriever.class)
    public AutoMemoryConsolidationCandidateRetriever shadowAutoMemoryCandidateRetriever(
            AutoMemoryQueryPort memories,
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorCandidateHydrationPort hydration,
            AutoMemoryVectorShadowTelemetry telemetry
    ) {
        return new ShadowAutoMemoryConsolidationCandidateRetriever(
                new ScopedAutoMemoryConsolidationCandidateRetriever(memories),
                vectors,
                hydration,
                telemetry);
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.vector.canary-enabled", havingValue = "true")
    @ConditionalOnMissingBean(AutoMemoryConsolidationCandidateRetriever.class)
    public AutoMemoryConsolidationCandidateRetriever canaryAutoMemoryCandidateRetriever(
            AutoMemoryQueryPort memories,
            AutoMemoryVectorStorePort vectors,
            AutoMemoryVectorCandidateHydrationPort hydration
    ) {
        return new CanaryAutoMemoryConsolidationCandidateRetriever(
                new ScopedAutoMemoryConsolidationCandidateRetriever(memories),
                vectors,
                hydration);
    }
}
