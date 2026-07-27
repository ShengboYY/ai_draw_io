package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.context.ContextCandidateQueryPort;
import org.zipp.ai.application.turn.context.ContextReadSetCommitPort;
import org.zipp.ai.application.turn.context.ContextReadSetMaterializerPort;
import org.zipp.ai.application.turn.context.ContextReadSetQueryPort;
import org.zipp.ai.application.turn.context.DefaultBaseTurnContextAssembler;

/** Composes Context preparation only when all server-owned context seams are available. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "turn-engine.execution.enabled", havingValue = "true")
public class ContextAssemblyCompositionConfig {

    @Bean
    public DefaultBaseTurnContextAssembler baseTurnContextAssembler(
            ContextReadSetQueryPort readSets,
            ContextReadSetCommitPort readSetCommit,
            ContextCandidateQueryPort candidates,
            ContextReadSetMaterializerPort materializer
    ) {
        return new DefaultBaseTurnContextAssembler(readSets, readSetCommit, candidates, materializer);
    }
}
