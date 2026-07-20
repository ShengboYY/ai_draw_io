package org.zipp.ai.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.operations.*;
import org.zipp.ai.trigger.http.service.MaterialOperationsMonitor;
import org.zipp.ai.infrastructure.adapter.telemetry.MaterialOperationsMetrics;

import java.time.Clock;
import java.time.Duration;

/** WP8 rollout, capacity, release-gate and capability-dashboard wiring. */
@Configuration
public class MaterialOperationsConfig {

    @Bean
    public MaterialFeatureSet materialFeatureSet(
            @Value("${app.material-operations.ingestion-enabled:false}") boolean ingestion,
            @Value("${app.material-upload.enabled:false}") boolean upload,
            @Value("${app.material-catalog.enabled:false}") boolean library,
            @Value("${app.material-lifecycle.enabled:false}") boolean lifecycle,
            @Value("${app.material-rag.enabled:false}") boolean retrieval,
            @Value("${app.material-operations.retrieval-shadow-enabled:false}") boolean retrievalShadow,
            @Value("${app.material-rag.citation-commit-enabled:false}") boolean citationCommit,
            @Value("${app.material-rag.evidence-answer-enabled:false}") boolean evidenceAnswer,
            @Value("${app.material-upload.anonymous-enabled:false}") boolean anonymousUpload) {
        return new MaterialFeatureSet(ingestion, upload, library, lifecycle, retrieval, retrievalShadow,
                citationCommit, evidenceAnswer, anonymousUpload);
    }

    @Bean
    public MaterialReleaseApproval materialReleaseApproval(
            @Value("${app.material-operations.release-approved:false}") boolean approved,
            @Value("${app.material-operations.release-report-version:}") String reportVersion) {
        return new MaterialReleaseApproval(approved, reportVersion);
    }

    @Bean
    public MaterialCapacitySnapshotPort materialCapacitySnapshotPort(
            MaterialOperationsSnapshotPort operations,
            MaterialProviderCapacityFeed providerCapacity,
            @Value("${app.material-operations.capacity.monthly-indexed-pages-limit:7000}") long pageLimit,
            @Value("${app.material-operations.capacity.provider-max-age-seconds:180}") long maxAgeSeconds) {
        if (pageLimit < 1 || maxAgeSeconds < 1) {
            // Bad material capacity configuration degrades that subsystem without taking down text drawing.
            return () -> { throw new IllegalStateException("material capacity configuration is invalid"); };
        }
        return new CombinedMaterialCapacitySnapshotPort(operations, providerCapacity, pageLimit,
                Duration.ofSeconds(maxAgeSeconds), Clock.systemUTC());
    }

    @Bean
    public MaterialCapacityBreaker materialCapacityBreaker(MaterialCapacitySnapshotPort snapshots) {
        return new MaterialCapacityBreaker(snapshots);
    }

    @Bean
    public MaterialRolloutGate materialRolloutGate(MaterialFeatureSet features,
                                                   MaterialReleaseApproval approval) {
        return new MaterialRolloutGate(features, approval);
    }

    @Bean
    public MaterialCapabilityService materialCapabilityService(MaterialOperationsSnapshotPort operations,
                                                               MaterialCapacityBreaker breaker) {
        return new MaterialCapabilityService(operations, breaker);
    }

    @Bean
    public RagBetaReleaseGate ragBetaReleaseGate() {
        return new RagBetaReleaseGate();
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-operations.enabled", havingValue = "true")
    public MaterialOperationsMetrics materialOperationsMetrics(MeterRegistry registry) {
        return new MaterialOperationsMetrics(registry);
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-operations.enabled", havingValue = "true")
    public MaterialOperationsMonitor materialOperationsMonitor(MaterialCapabilityService capabilities,
                                                               MaterialFeatureSet featureSet,
                                                               MaterialReleaseApproval releaseApproval,
                                                               MaterialOperationsMetrics metrics) {
        return new MaterialOperationsMonitor(capabilities, featureSet, releaseApproval, metrics);
    }
}
