package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.material.port.*;
import org.zipp.ai.domain.material.service.*;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class MaterialLifecycleConfig {
    /** Always present so account deletion fails closed even when user-facing material APIs are disabled. */
    @Bean
    public MaterialLifecycleService materialLifecycleService(MaterialLifecyclePort lifecycle,
                                                             CatalogIdFactory ids,
                                                             ObjectProvider<Clock> clocks) {
        return new MaterialLifecycleService(lifecycle, ids, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-lifecycle.enabled", havingValue = "true")
    public MaterialReadLeaseService materialReadLeaseService(MaterialReadLeasePort leases,
                                                             CatalogIdFactory ids,
                                                             ObjectProvider<Clock> clocks) {
        return new MaterialReadLeaseService(leases, ids, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-lifecycle.enabled", havingValue = "true")
    public MaterialDeletionConfirmationService materialDeletionConfirmationService(
            @Value("${app.material-lifecycle.deletion-confirmation-secret}") String secret,
            ObjectProvider<Clock> clocks) {
        return new MaterialDeletionConfirmationService(secret,
                clocks.getIfAvailable(Clock::systemUTC), Duration.ofMinutes(10));
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-lifecycle.enabled", havingValue = "true")
    public MaterialDeletionService materialDeletionService(MaterialLifecyclePort lifecycle,
                                                           MaterialLifecycleService commands,
                                                           MaterialDeletionConfirmationService confirmations) {
        return new MaterialDeletionService(lifecycle, commands, confirmations);
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-lifecycle.enabled", havingValue = "true")
    public MaterialLifecycleMaintenanceJob materialLifecycleMaintenanceJob(
            MaterialLifecycleService lifecycle, MaterialReadLeaseService leases,
            @Value("${app.material-lifecycle.batch-size:100}") int batchSize) {
        return new MaterialLifecycleMaintenanceJob(lifecycle, leases, batchSize);
    }
}
