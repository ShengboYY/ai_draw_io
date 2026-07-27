package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.material.port.MaterialPreviewContentPort;
import org.zipp.ai.domain.material.service.MaterialPreviewService;
import org.zipp.ai.domain.material.service.MaterialRevisionPolicy;
import org.zipp.ai.infrastructure.adapter.filesystem.FileSystemMaterialObjectAdapter;
import org.zipp.ai.infrastructure.adapter.s3.BoundedPngPreviewAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(name = {"app.material-catalog.enabled", "app.material-preview.enabled"},
        havingValue = "true")
public class MaterialPreviewConfig {
    @Bean
    @ConditionalOnProperty(name = "app.material-storage.storage", havingValue = "s3")
    public S3Client materialPreviewS3Client(@Value("${app.material-preview.aws-region}") String region) {
        return S3Client.builder().credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region)).build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.material-storage.storage", havingValue = "s3")
    public RevisionArtifactPort materialPreviewRevisionArtifactPort(
            @Qualifier("materialPreviewS3Client") S3Client s3Client,
            @Value("${app.material-preview.materials-bucket}") String materialsBucket) {
        return new S3RevisionArtifactAdapter(s3Client, materialsBucket);
    }

    @Bean("materialPreviewRevisionArtifactPort")
    @ConditionalOnProperty(name = "app.material-storage.storage", havingValue = "local", matchIfMissing = true)
    public RevisionArtifactPort materialPreviewLocalRevisionArtifactPort(
            @Value("${app.material-upload.local-root:./data/material-uploads}") String quarantineRoot,
            @Value("${app.material-storage.local-root:./data/material-objects}") String materialsRoot) {
        return new FileSystemMaterialObjectAdapter(Path.of(quarantineRoot), Path.of(materialsRoot));
    }

    @Bean
    public MaterialPreviewContentPort materialPreviewContentPort(
            @Qualifier("materialPreviewRevisionArtifactPort") RevisionArtifactPort artifacts) {
        return new BoundedPngPreviewAdapter(artifacts);
    }

    @Bean
    public ProcessingRevisionProfile materialTargetProcessingProfile(
            @Value("${app.material-preview.processing-profile.fingerprint}") String fingerprint,
            @Value("${app.material-preview.processing-profile.parser-version}") String parserVersion,
            @Value("${app.material-preview.processing-profile.cleaner-version}") String cleanerVersion,
            @Value("${app.material-preview.processing-profile.chunk-schema-version}") String chunkSchemaVersion,
            @Value("${app.material-preview.processing-profile.ocr-version}") String ocrVersion,
            @Value("${app.material-preview.processing-profile.vlm-schema-version}") String vlmSchemaVersion) {
        // App and Worker must publish the same immutable target profile before reprocessing is enabled.
        return new ProcessingRevisionProfile(fingerprint, parserVersion, cleanerVersion,
                chunkSchemaVersion, ocrVersion, vlmSchemaVersion);
    }

    @Bean
    public MaterialPreviewService materialPreviewService(MaterialPageAccessPort pages,
                                                         MaterialPreviewContentPort previews,
                                                         ProcessingRevisionProfile targetProcessingProfile,
                                                         CatalogIdFactory ids,
                                                         ObjectProvider<Clock> clocks) {
        return new MaterialPreviewService(pages, previews, new MaterialRevisionPolicy(),
                targetProcessingProfile, ids,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}
