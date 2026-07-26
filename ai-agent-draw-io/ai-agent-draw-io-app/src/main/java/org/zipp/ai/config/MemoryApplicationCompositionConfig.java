package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.memory.MemoryCandidateService;
import org.zipp.ai.application.memory.MemoryCandidateStorePort;
import org.zipp.ai.application.memory.MemoryProposalService;

/** Composes the explicit Memory confirmation and management boundary after the Chartbook release. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
@ConditionalOnBean(MemoryCandidateStorePort.class)
public class MemoryApplicationCompositionConfig {

    @Bean
    public MemoryProposalService memoryProposalService(MemoryCandidateStorePort store) {
        return new MemoryProposalService(store);
    }

    @Bean
    public MemoryCandidateService memoryCandidateService(MemoryCandidateStorePort store) {
        return new MemoryCandidateService(store);
    }
}
