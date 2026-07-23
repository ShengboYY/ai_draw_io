package org.zipp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.multimodal.*;
import org.zipp.ai.infrastructure.adapter.s3.RevisionVisualArtifactReaderAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared exact-pixel visual boundary used by visual RAG and direct conversion. */
@Configuration
@ConditionalOnExpression("'${app.material-lifecycle.enabled:false}' == 'true' and "
        + "'${app.material-visual-observation.enabled:false}' == 'true'")
public class MaterialVisualObservationConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService visualObservationExecutor(
            @Value("${app.material-visual-observation.parallelism:2}") int parallelism) {
        return Executors.newFixedThreadPool(Math.max(1, Math.min(4, parallelism)));
    }

    @Bean
    public S3Client materialVisualS3Client(
            @Value("${app.material-visual-observation.aws-region}") String region,
            @Value("${MATERIAL_S3_ENDPOINT:}") String endpoint) {
        var builder = S3Client.builder().credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            // Local S3-compatible stores use path-style addressing; production leaves this unset.
            builder.endpointOverride(URI.create(endpoint.trim())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    public RevisionArtifactPort materialVisualRevisionArtifactPort(
            @Qualifier("materialVisualS3Client") S3Client s3,
            @Value("${app.material-visual-observation.materials-bucket}") String bucket) {
        return new S3RevisionArtifactAdapter(s3, bucket);
    }

    @Bean
    public VisualArtifactReaderPort visualArtifactReader(
            @Qualifier("materialVisualRevisionArtifactPort") RevisionArtifactPort artifacts) {
        return new RevisionVisualArtifactReaderAdapter(artifacts);
    }

    @Bean
    public VisionModelPort visionModelPort(
            IChatService chat, ObjectMapper mapper,
            @Value("${MATERIAL_VISUAL_OBSERVATION_AGENT_ID:}") String agentId) {
        return new ChatVisionModelPortAdapter(chat, mapper, agentId);
    }

    @Bean
    public VisualObservationModule visualObservationModule(
            VisualArtifactReaderPort artifacts, VisionModelPort model,
            @Qualifier("visualObservationExecutor") ExecutorService executor,
            @Value("${app.material-visual-observation.timeout-ms:30000}") long timeoutMs) {
        return new DefaultVisualObservationModule(artifacts, model, executor, timeoutMs);
    }
}
