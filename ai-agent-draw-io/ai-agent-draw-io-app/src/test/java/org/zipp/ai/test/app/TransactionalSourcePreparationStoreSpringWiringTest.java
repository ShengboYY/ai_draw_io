package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.zipp.ai.domain.multimodal.DirectSourcePreparationModule;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectPreparationStore;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectVisionAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlEvidencePreparationStore;
import org.zipp.ai.infrastructure.adapter.repository.MySqlSourceAwarePreparationAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TransactionalSourcePreparationStoreSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionalStoreConfiguration.class);

    @Test
    void sourcePreparationRepositoriesCanBeCglibProxied() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(MySqlDirectPreparationStore.class))).isTrue();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(MySqlEvidencePreparationStore.class))).isTrue();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(MySqlDirectVisionAdapter.class))).isTrue();
            assertThat(AopUtils.isCglibProxy(
                    context.getBean(MySqlSourceAwarePreparationAdapter.class))).isTrue();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({
            MySqlDirectPreparationStore.class,
            MySqlEvidencePreparationStore.class,
            MySqlSourceAwarePreparationAdapter.class
    })
    static class TransactionalStoreConfiguration {

        @Bean
        static PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslation() {
            // Production enables class-based repository proxies, so this test must exercise CGLIB too.
            PersistenceExceptionTranslationPostProcessor postProcessor =
                    new PersistenceExceptionTranslationPostProcessor();
            postProcessor.setProxyTargetClass(true);
            return postProcessor;
        }

        @Bean
        JdbcOperations jdbcOperations() {
            return mock(JdbcOperations.class);
        }

        @Bean
        RequestSourceSnapshotStore requestSourceSnapshotStore() {
            return mock(RequestSourceSnapshotStore.class);
        }

        @Bean
        DirectSourcePreparationModule directSourcePreparationModule() {
            return mock(DirectSourcePreparationModule.class);
        }

        @Bean
        MySqlDirectVisionAdapter mySqlDirectVisionAdapter(
                DirectSourcePreparationModule preparation,
                RequestSourceSnapshotStore snapshots,
                MySqlDirectPreparationStore store
        ) {
            // Explicit construction avoids test configuration ordering obscuring @ConditionalOnBean.
            return new MySqlDirectVisionAdapter(preparation, snapshots, store);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
