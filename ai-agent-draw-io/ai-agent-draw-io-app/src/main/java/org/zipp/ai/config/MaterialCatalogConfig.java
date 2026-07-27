package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.chartbook.service.ChartbookCatalogService;
import org.zipp.ai.domain.chartbook.service.ChartbookFileModule;
import org.zipp.ai.domain.chartbook.service.ChartbookProfileService;
import org.zipp.ai.domain.chartbook.service.DefaultChartbookFileModule;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.port.MaterialDownloadPort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialDownloadService;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;
import org.zipp.ai.infrastructure.adapter.filesystem.FileSystemMaterialObjectAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class MaterialCatalogConfig {
    @Bean
    @ConditionalOnProperty(name = "app.material-storage.storage", havingValue = "s3")
    public S3Client materialDownloadS3Client(
            @Value("${app.material-rag.aws-region}") String region,
            @Value("${MATERIAL_S3_ENDPOINT:}") String endpoint) {
        var builder = S3Client.builder().credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean("materialDownloadRevisionArtifactPort")
    @ConditionalOnProperty(name = "app.material-storage.storage", havingValue = "s3")
    public RevisionArtifactPort materialDownloadRevisionArtifactPort(
            @Qualifier("materialDownloadS3Client") S3Client s3,
            @Value("${app.material-rag.materials-bucket}") String bucket) {
        return new S3RevisionArtifactAdapter(s3, bucket);
    }

    @Bean("materialDownloadRevisionArtifactPort")
    @ConditionalOnProperty(name = "app.material-storage.storage", havingValue = "local", matchIfMissing = true)
    public RevisionArtifactPort materialDownloadLocalRevisionArtifactPort(
            @Value("${app.material-upload.local-root:./data/material-uploads}") String quarantineRoot,
            @Value("${app.material-storage.local-root:./data/material-objects}") String materialsRoot) {
        return new FileSystemMaterialObjectAdapter(Path.of(quarantineRoot), Path.of(materialsRoot));
    }

    @Bean
    public MaterialCatalogService materialCatalogService(MaterialCatalogPort catalog,
                                                         CatalogIdFactory ids) {
        return new MaterialCatalogService(catalog, ids, new MaterialScopePolicy());
    }

    @Bean
    public MaterialDownloadService materialDownloadService(MaterialDownloadPort downloads) {
        return new MaterialDownloadService(downloads);
    }

    @Bean
    public ChartbookFileModule chartbookFileModule(ChartbookCatalogPort chartbooks,
                                                   MaterialCatalogService materials,
                                                   MaterialLifecycleService lifecycle) {
        return new DefaultChartbookFileModule(chartbooks, materials, lifecycle);
    }

    @Bean
    public ChartbookCatalogService chartbookCatalogService(ChartbookCatalogPort chartbooks,
                                                           MaterialCatalogService materials,
                                                           ChartbookFileModule files,
                                                           CatalogIdFactory ids,
                                                           ObjectProvider<Clock> clocks) {
        return new ChartbookCatalogService(chartbooks, materials, files, ids,
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    public ChartbookProfileService chartbookProfileService(
            org.zipp.ai.domain.chartbook.port.ChartbookProfilePort profiles,
            ObjectProvider<Clock> clocks) {
        return new ChartbookProfileService(profiles, clocks.getIfAvailable(Clock::systemUTC));
    }
}
