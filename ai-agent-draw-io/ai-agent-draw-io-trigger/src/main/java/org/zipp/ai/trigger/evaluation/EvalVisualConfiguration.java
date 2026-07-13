package org.zipp.ai.trigger.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.agent.service.evaluation.visual.DrawioSvgRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;

/** Keeps the deterministic renderer injectable without coupling the domain implementation to Spring. */
@Configuration
public class EvalVisualConfiguration {
    @Bean public IDiagramImageRenderer evalDiagramImageRenderer() { return new DrawioSvgRenderer(); }
}
