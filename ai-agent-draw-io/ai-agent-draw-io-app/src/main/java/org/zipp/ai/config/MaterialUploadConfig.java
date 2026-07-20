package org.zipp.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.ingestion.model.valobj.UploadLimits;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectPort;
import org.zipp.ai.domain.ingestion.port.UploadPolicySignerPort;
import org.zipp.ai.domain.ingestion.port.UploadScopeAuthorizer;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;
import org.zipp.ai.domain.ingestion.port.MaterialUploadTelemetry;
import org.zipp.ai.domain.ingestion.service.DefaultMaterialUploadService;
import org.zipp.ai.domain.ingestion.service.IMaterialUploadService;
import org.zipp.ai.domain.ingestion.service.UploadAdmissionPolicy;
import org.zipp.ai.domain.ingestion.service.UploadIdFactory;
import org.zipp.ai.domain.operations.MaterialCapacityBreaker;
import org.zipp.ai.domain.operations.MaterialRolloutGate;
import org.zipp.ai.infrastructure.adapter.s3.S3BrowserPostPolicySigner;
import org.zipp.ai.infrastructure.adapter.s3.S3QuarantineObjectAdapter;
import org.zipp.ai.infrastructure.adapter.s3.SecureUploadIdFactory;
import org.zipp.ai.trigger.http.service.DailyHmacRateKeyFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Clock;

@Configuration
@ConditionalOnProperty(name = "app.material-upload.enabled", havingValue = "true")
public class MaterialUploadConfig {

    @Bean
    public Clock materialUploadClock() {
        return Clock.systemUTC();
    }

    @Bean
    public AwsCredentialsProvider materialUploadCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public Region materialUploadRegion(@Value("${app.material-upload.aws-region}") String region) {
        return Region.of(region);
    }

    @Bean
    public S3Client materialUploadS3Client(AwsCredentialsProvider credentialsProvider, Region region) {
        return S3Client.builder().credentialsProvider(credentialsProvider).region(region).build();
    }

    @Bean
    public QuarantineObjectPort quarantineObjectPort(
            @Qualifier("materialUploadS3Client") S3Client s3Client) {
        return new S3QuarantineObjectAdapter(s3Client);
    }

    @Bean
    public UploadPolicySignerPort uploadPolicySigner(AwsCredentialsProvider credentialsProvider,
                                                     Region region, Clock clock) {
        return new S3BrowserPostPolicySigner(credentialsProvider, region, clock);
    }

    @Bean
    public UploadIdFactory uploadIdFactory(@Value("${app.material-upload.owner-path-secret}") String secret) {
        return new SecureUploadIdFactory(secret);
    }

    @Bean
    public DailyHmacRateKeyFactory dailyHmacRateKeyFactory(
            @Value("${app.material-upload.rate-key-secret}") String secret, Clock clock) {
        return new DailyHmacRateKeyFactory(secret, clock);
    }

    @Bean
    public IMaterialUploadService materialUploadService(
            UploadSessionStore sessionStore,
            QuarantineObjectPort quarantineObjectPort,
            UploadPolicySignerPort uploadPolicySignerPort,
            UploadScopeAuthorizer scopeAuthorizer,
            UploadIdFactory idFactory,
            Clock clock,
            MaterialCapacityBreaker capacityBreaker,
            MaterialRolloutGate rolloutGate,
            ObjectProvider<MaterialUploadTelemetry> telemetry,
            @Value("${app.material-upload.quarantine-bucket}") String quarantineBucket,
            @Value("${app.material-upload.anonymous-enabled:false}") boolean anonymousEnabled) {
        return new DefaultMaterialUploadService(sessionStore, quarantineObjectPort, uploadPolicySignerPort,
                scopeAuthorizer, new UploadAdmissionPolicy(UploadLimits.defaults(anonymousEnabled)),
                idFactory, clock, quarantineBucket, capacityBreaker, rolloutGate,
                telemetry.getIfAvailable(() -> MaterialUploadTelemetry.NOOP));
    }
}
