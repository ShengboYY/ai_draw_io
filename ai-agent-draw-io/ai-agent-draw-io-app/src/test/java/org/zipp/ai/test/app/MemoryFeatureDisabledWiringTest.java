package org.zipp.ai.test.app;

import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.memory.MemoryCandidateStorePort;
import org.zipp.ai.application.memory.MemoryProposalService;
import org.zipp.ai.config.MemoryApplicationCompositionConfig;
import org.zipp.ai.infrastructure.adapter.repository.MySqlCompletedTurnMemoryProposalWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class MemoryFeatureDisabledWiringTest {

    @Test
    public void writerDoesNotBreakStartupWhenCatalogIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("app.material-catalog.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MySqlCompletedTurnMemoryProposalWriter.class);
                    assertThat(context).doesNotHaveBean(MemoryProposalService.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            MemoryApplicationCompositionConfig.class,
            MySqlCompletedTurnMemoryProposalWriter.class
    })
    static class TestConfiguration {

        @Bean
        JdbcOperations jdbcOperations() {
            return mock(JdbcOperations.class);
        }

        @Bean
        MemoryCandidateStorePort memoryCandidateStorePort() {
            return mock(MemoryCandidateStorePort.class);
        }
    }
}
