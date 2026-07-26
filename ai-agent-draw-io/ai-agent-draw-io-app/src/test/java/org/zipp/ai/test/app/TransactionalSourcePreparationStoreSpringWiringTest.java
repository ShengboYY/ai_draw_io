package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectPreparationStore;
import org.zipp.ai.infrastructure.adapter.repository.MySqlEvidencePreparationStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TransactionalSourcePreparationStoreSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionalStoreConfiguration.class);

    @Test
    void transactionalPreparationStoresCanBeCglibProxied() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(MySqlDirectPreparationStore.class))).isTrue();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(MySqlEvidencePreparationStore.class))).isTrue();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({MySqlDirectPreparationStore.class, MySqlEvidencePreparationStore.class})
    static class TransactionalStoreConfiguration {

        @Bean
        JdbcOperations jdbcOperations() {
            return mock(JdbcOperations.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
