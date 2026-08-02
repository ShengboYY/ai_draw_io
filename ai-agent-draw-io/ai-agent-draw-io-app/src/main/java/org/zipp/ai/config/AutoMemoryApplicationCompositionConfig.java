package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.memory.AutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.AutoMemoryExtractionPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionEligibilityPolicy;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorker;
import org.zipp.ai.application.memory.AutoMemoryManagementService;
import org.zipp.ai.application.memory.AutoMemoryManagementStorePort;
import org.zipp.ai.application.memory.AutoMemoryMaintenancePort;
import org.zipp.ai.application.memory.AutoMemoryMaintenanceService;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryObservationStorePort;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.ScopedAutoMemoryConsolidationCandidateRetriever;

import java.time.Clock;
import java.time.Duration;

/** Composes Auto Memory independently from the legacy material-catalog feature boundary. */
@Configuration(proxyBeanMethods = false)
public class AutoMemoryApplicationCompositionConfig {

    @Bean
    @ConditionalOnMissingBean(AutoMemoryExtractionWorkPort.class)
    public AutoMemoryExtractionWorkPort noOpAutoMemoryExtractionWorkPort() {
        // Terminal commit adapters always receive a port; disabled deployments never touch new DDL.
        return AutoMemoryExtractionWorkPort.NOOP;
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
    public AutoMemoryObservationService autoMemoryObservationService(
            AutoMemoryObservationStorePort store
    ) {
        return new AutoMemoryObservationService(store);
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
    public AutoMemoryManagementService autoMemoryManagementService(
            AutoMemoryManagementStorePort store
    ) {
        return new AutoMemoryManagementService(store);
    }

    @Bean
    @ConditionalOnExpression(
            "${app.memory.auto-enabled:false} && "
                    + "(!${app.memory.vector.projection-enabled:false} "
                    + "|| (!${app.memory.vector.shadow-enabled:false} "
                    + "&& !${app.memory.vector.canary-enabled:false}))")
    @ConditionalOnMissingBean(AutoMemoryConsolidationCandidateRetriever.class)
    public AutoMemoryConsolidationCandidateRetriever autoMemoryConsolidationCandidateRetriever(
            AutoMemoryQueryPort memories
    ) {
        // Keep deterministic SQL retrieval until an enabled vector mode explicitly replaces it.
        return new ScopedAutoMemoryConsolidationCandidateRetriever(memories);
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
    public AutoMemoryExtractionWorker autoMemoryExtractionWorker(
            AutoMemoryExtractionWorkPort work,
            AutoMemoryExtractionPort extractor,
            AutoMemoryConsolidationCandidateRetriever candidates,
            AutoMemoryObservationService observations,
            ObjectProvider<Clock> clocks
    ) {
        return new AutoMemoryExtractionWorker(
                work,
                extractor,
                candidates,
                observations,
                new AutoMemoryExtractionEligibilityPolicy(),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
    public AutoMemoryExtractionJob autoMemoryExtractionJob(
            AutoMemoryExtractionWorker worker
    ) {
        return new AutoMemoryExtractionJob(worker);
    }

    @Bean
    @ConditionalOnProperty(
            name = {"app.memory.auto-enabled", "app.memory.aging-enabled"},
            havingValue = "true")
    public AutoMemoryMaintenanceService autoMemoryMaintenanceService(
            AutoMemoryMaintenancePort maintenance,
            ObjectProvider<Clock> clocks,
            @Value("${app.memory.observed-retention-days:90}") long retentionDays
    ) {
        return new AutoMemoryMaintenanceService(
                maintenance,
                clocks.getIfAvailable(Clock::systemUTC),
                Duration.ofDays(retentionDays));
    }

    @Bean
    @ConditionalOnProperty(
            name = {"app.memory.auto-enabled", "app.memory.aging-enabled"},
            havingValue = "true")
    public AutoMemoryMaintenanceJob autoMemoryMaintenanceJob(
            AutoMemoryMaintenanceService maintenance,
            @Value("${app.memory.aging-batch-size:100}") int batchSize
    ) {
        return new AutoMemoryMaintenanceJob(maintenance, batchSize);
    }
}
