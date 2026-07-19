package org.zipp.ai.ingestion.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.ingestion.port.PinnedQuarantineContentPort;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.SecureUploadWorkPort;
import org.zipp.ai.domain.ingestion.port.MaterializationWorkPort;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import org.zipp.ai.infrastructure.adapter.s3.S3OriginalPromotionAdapter;
import org.zipp.ai.infrastructure.adapter.s3.S3PinnedQuarantineContentAdapter;
import org.zipp.ai.ingestion.worker.security.ClamAvScannerAdapter;
import org.zipp.ai.ingestion.worker.security.SecureFileValidator;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class WorkerConfig {

    @Bean
    public Clock workerClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    public S3Client workerS3Client(@Value("${worker.aws-region}") String region) {
        return S3Client.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create()).build();
    }

    @Bean
    public PinnedQuarantineContentPort pinnedQuarantineContentPort(S3Client s3Client) {
        return new S3PinnedQuarantineContentAdapter(s3Client);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.materialization-enabled", havingValue = "true")
    public OriginalPromotionPort originalPromotionPort(
            S3Client s3Client, @Value("${worker.materials-bucket}") String materialsBucket) {
        return new S3OriginalPromotionAdapter(s3Client, materialsBucket);
    }

    @Bean
    public SecureFileValidator secureFileValidator(@Value("${worker.clamav.host}") String host,
                                                   @Value("${worker.clamav.port:3310}") int port,
                                                   @Value("${worker.clamav.timeout-seconds:60}") long timeoutSeconds) {
        return new SecureFileValidator(new ClamAvScannerAdapter(host, port, Duration.ofSeconds(timeoutSeconds)));
    }

    @Bean
    public SecureUploadJobHandler secureUploadJobHandler(SecureUploadWorkPort uploads,
                                                         PinnedQuarantineContentPort content,
                                                         ProcessingQueuePort queue,
                                                         SecureFileValidator validator,
                                                         Clock clock) {
        return new SecureUploadJobHandler(uploads, content, queue, validator, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "worker.materialization-enabled", havingValue = "true")
    public MaterializationJobHandler materializationJobHandler(MaterializationWorkPort work,
                                                               OriginalPromotionPort promotion,
                                                               PinnedQuarantineContentPort content,
                                                               ProcessingQueuePort queue,
                                                               Clock clock) {
        return new MaterializationJobHandler(work, promotion, content, queue, clock);
    }

    @Bean
    public WorkerPoller workerPoller(ProcessingQueuePort queue,
                                     SecureUploadJobHandler secureUploadHandler,
                                     ObjectProvider<MaterializationJobHandler> materializationHandler,
                                     Clock clock,
                                     @Value("${worker.id}") String workerId,
                                     @Value("${worker.materialization-enabled:false}") boolean materializationEnabled) {
        return new WorkerPoller(queue, secureUploadHandler, materializationHandler.getIfAvailable(), clock, workerId,
                materializationEnabled);
    }
}
