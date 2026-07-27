package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectPreparationStore;
import org.zipp.ai.infrastructure.adapter.repository.MySqlEvidencePreparationStore;
import org.zipp.ai.infrastructure.turn.model.PreparedDirectGenerationAdapter;
import org.zipp.ai.infrastructure.turn.model.ChatEvidenceAnswerGenerationAdapter;
import org.zipp.ai.infrastructure.turn.model.ChatGroundedGenerationAdapter;
import org.zipp.ai.infrastructure.turn.model.ChatPlainGenerationAdapter;
import org.zipp.ai.infrastructure.turn.model.ChatPlainResponseAdapter;
import org.zipp.ai.infrastructure.turn.model.ChatSemanticIntentRouterAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TurnModelAdapterSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AdapterScanConfiguration.class);

    @Test
    void componentScanSelectsProductionConstructorsForAllV2ModelAdapters() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(ChatSemanticIntentRouterAdapter.class)
                .hasSingleBean(ChatPlainGenerationAdapter.class)
                .hasSingleBean(ChatPlainResponseAdapter.class)
                .hasSingleBean(PreparedDirectGenerationAdapter.class)
                .hasSingleBean(ChatGroundedGenerationAdapter.class)
                .hasSingleBean(ChatEvidenceAnswerGenerationAdapter.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ChatSemanticIntentRouterAdapter.class)
    static class AdapterScanConfiguration {

        @Bean
        IChatService chatService() {
            IChatService chat = mock(IChatService.class);
            when(chat.isAgentToolFree(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            return chat;
        }

        @Bean
        MySqlDirectPreparationStore directPreparationStore() {
            return mock(MySqlDirectPreparationStore.class);
        }

        @Bean
        MySqlEvidencePreparationStore evidencePreparationStore() {
            return mock(MySqlEvidencePreparationStore.class);
        }
    }
}
