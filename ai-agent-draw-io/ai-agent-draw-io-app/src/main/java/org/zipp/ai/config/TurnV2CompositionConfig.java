package org.zipp.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.classification.SemanticIntentRouterPort;
import org.zipp.ai.application.turn.classification.TurnClassificationService;
import org.zipp.ai.application.turn.context.DefaultRestrictedSourceDemandInputFactory;
import org.zipp.ai.application.turn.context.DefaultSemanticRouterContextProjector;
import org.zipp.ai.application.turn.context.RestrictedSourceDemandInputFactory;
import org.zipp.ai.application.turn.context.SemanticRouterContextProjector;
import org.zipp.ai.application.turn.demand.DemandResolutionPolicy;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterPort;
import org.zipp.ai.application.turn.demand.SourceDemandResolver;

/** Isolated V2 model graph; no legacy route is switched by registering these beans. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({SemanticIntentRouterPort.class, SourceDemandInterpreterPort.class})
public class TurnV2CompositionConfig {

    @Bean
    public SemanticRouterContextProjector semanticRouterContextProjector() {
        return new DefaultSemanticRouterContextProjector();
    }

    @Bean
    public RestrictedSourceDemandInputFactory restrictedSourceDemandInputFactory() {
        return new DefaultRestrictedSourceDemandInputFactory();
    }

    @Bean
    public SourceDemandResolver sourceDemandResolver() {
        return new SourceDemandResolver();
    }

    @Bean
    public DemandResolutionPolicy demandResolutionPolicy() {
        return DemandResolutionPolicy.m2Default();
    }

    @Bean
    public TurnClassificationService turnClassificationService(
            SemanticIntentRouterPort router,
            SourceDemandInterpreterPort demandInterpreter,
            SourceDemandResolver resolver,
            DemandResolutionPolicy policy
    ) {
        return new TurnClassificationService(router, demandInterpreter, resolver, policy);
    }
}
