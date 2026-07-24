package org.zipp.ai.test.app;

import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.zipp.ai.config.MaterialUploadConfig;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectPort;
import org.zipp.ai.domain.ingestion.port.UploadScopeAuthorizer;
import org.zipp.ai.domain.ingestion.port.UploadSessionStore;
import org.zipp.ai.domain.operations.MaterialCapacityBreaker;
import org.zipp.ai.domain.operations.MaterialRolloutGate;
import org.zipp.ai.infrastructure.adapter.filesystem.FileSystemQuarantineObjectAdapter;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class MaterialUploadLocalConfigTest {

    @Test
    public void localStorageStartsWithoutAwsClient() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "app.material-upload.enabled=true",
                        "app.material-upload.storage=local",
                        "app.material-upload.local-root=${java.io.tmpdir}/ai-drawio-local-upload-test",
                        "app.material-upload.local-bucket=quarantine",
                        "app.material-upload.owner-path-secret=owner-secret-012345678901234567890123456789",
                        "app.material-upload.rate-key-secret=rate-secret-012345678901234567890123456789")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(S3Client.class);
                    assertThat(context.getBean(QuarantineObjectPort.class))
                            .isInstanceOf(FileSystemQuarantineObjectAdapter.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MaterialUploadConfig.class)
    static class TestConfiguration {

        @Bean
        UploadSessionStore uploadSessionStore() {
            return mock(UploadSessionStore.class);
        }

        @Bean
        UploadScopeAuthorizer uploadScopeAuthorizer() {
            return mock(UploadScopeAuthorizer.class);
        }

        @Bean
        MaterialCapacityBreaker materialCapacityBreaker() {
            return mock(MaterialCapacityBreaker.class);
        }

        @Bean
        MaterialRolloutGate materialRolloutGate() {
            return mock(MaterialRolloutGate.class);
        }
    }
}
