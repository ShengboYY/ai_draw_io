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
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.material.service.MaterialReadLeaseService;
import org.zipp.ai.domain.multimodal.*;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.internal.DefaultRequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;
import org.zipp.ai.domain.retrieval.port.RequestSourceResolutionPort;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;
import org.zipp.ai.infrastructure.adapter.repository.MaterialEvidenceReadLeaseCoordinator;

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
            VisualObservationModule observations, ImageToDiagramModule converter,
            RequestSourceResolutionService sourceResolution,
            EvidenceReadLeaseCoordinator leases, MaterialPageAccessPort pages) {
        return new DefaultDirectSourcePreparationModule(
                observations, converter, sourceResolution, leases, pages);
    }

    @Bean
    @ConditionalOnMissingBean(RequestSourceResolutionService.class)
    public RequestSourceResolutionService directRequestSourceResolutionService(
            RequestSourceResolutionPort catalog, RequestSourceSnapshotStore snapshots) {
        return new DefaultRequestSourceResolutionService(catalog, snapshots);
    }

    @Bean
    @ConditionalOnMissingBean(EvidenceReadLeaseCoordinator.class)
    public EvidenceReadLeaseCoordinator directEvidenceReadLeaseCoordinator(
            MaterialReadLeaseService leases) {
        return new MaterialEvidenceReadLeaseCoordinator(leases);
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
