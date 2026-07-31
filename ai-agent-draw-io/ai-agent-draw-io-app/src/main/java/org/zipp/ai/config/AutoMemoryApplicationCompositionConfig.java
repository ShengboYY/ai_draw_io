package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.memory.AutoMemoryExtractionPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorkPort;
import org.zipp.ai.application.memory.AutoMemoryExtractionWorker;
import org.zipp.ai.application.memory.AutoMemoryManagementService;
import org.zipp.ai.application.memory.AutoMemoryManagementStorePort;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryObservationStorePort;

import java.time.Clock;

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
    @ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
    public AutoMemoryExtractionWorker autoMemoryExtractionWorker(
            AutoMemoryExtractionWorkPort work,
            AutoMemoryExtractionPort extractor,
            AutoMemoryObservationService observations,
            ObjectProvider<Clock> clocks
    ) {
        return new AutoMemoryExtractionWorker(
                work, extractor, observations, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.auto-enabled", havingValue = "true")
    public AutoMemoryExtractionJob autoMemoryExtractionJob(
            AutoMemoryExtractionWorker worker
    ) {
        return new AutoMemoryExtractionJob(worker);
    }
}
