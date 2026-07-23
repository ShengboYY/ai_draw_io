package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.grounding.CanvasCommitModule;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;
import org.zipp.ai.domain.multimodal.*;

/** Direct conversion wiring remains independent from lexical and vector retrieval flags. */
@Configuration
@ConditionalOnExpression("'${app.material-lifecycle.enabled:false}' == 'true' and "
        + "'${app.material-visual-observation.enabled:false}' == 'true' and "
        + "'${app.material-direct-image-conversion.enabled:false}' == 'true'")
public class DirectImageConversionConfig {

    @Bean
    public ImageToDiagramModule imageToDiagramModule() {
        return new DefaultImageToDiagramModule();
    }

    @Bean
    public DirectSourcePreparationModule directSourcePreparationModule(
            VisualObservationModule observations, ImageToDiagramModule converter) {
        return new DefaultDirectSourcePreparationModule(observations, converter);
    }

    @Bean
    @ConditionalOnMissingBean(CitationGuard.class)
    public CitationGuard directCitationGuard(ObjectProvider<ClaimSupportVerifierPort> verifiers) {
        return new CitationGuard(verifiers.getIfAvailable(() -> requests -> java.util.List.of()));
    }

    @Bean
    @ConditionalOnMissingBean(CanvasCommitModule.class)
    public CanvasCommitModule directCanvasCommitModule(
            CanvasMutationGate mutationGate, CitationGuard citationGuard,
            GroundedCanvasCommitPort commitPort) {
        return new CanvasCommitModule(mutationGate, citationGuard, commitPort);
    }

    @Bean
    public DirectImageConversionExecutionModule directImageConversionExecutionModule(
            DirectSourcePreparationModule preparation, CanvasCommitModule commits,
            GroundedRunControlPort runs) {
        return new DefaultDirectImageConversionExecutionModule(preparation, commits, runs);
    }
}
