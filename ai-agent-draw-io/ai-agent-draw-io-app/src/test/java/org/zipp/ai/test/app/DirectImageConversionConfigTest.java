package org.zipp.ai.test.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.config.DirectImageConversionConfig;
import org.zipp.ai.config.MaterialVisualObservationConfig;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.grounding.CanvasCommitModule;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;
import org.zipp.ai.domain.multimodal.DirectImageConversionExecutionModule;
import org.zipp.ai.domain.multimodal.VisualObservationModule;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.material.service.MaterialReadLeaseService;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;
import org.zipp.ai.domain.retrieval.port.RequestSourceResolutionPort;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DirectImageConversionConfigTest {

    @Test
    void directExecutionIsWiredWhenRagIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DirectImageConversionConfig.class)
                .withPropertyValues(
                        "app.material-lifecycle.enabled=true",
                        "app.material-visual-observation.enabled=true",
                        "app.material-direct-image-conversion.enabled=true",
                        "app.material-rag.enabled=false")
                .withBean(VisualObservationModule.class,
                        () -> mock(VisualObservationModule.class))
                .withBean(CanvasCommitModule.class, () -> mock(CanvasCommitModule.class))
                .withBean(GroundedRunControlPort.class,
                        () -> mock(GroundedRunControlPort.class))
                .withBean(RequestSourceResolutionPort.class,
                        () -> mock(RequestSourceResolutionPort.class))
                .withBean(RequestSourceSnapshotStore.class,
                        () -> mock(RequestSourceSnapshotStore.class))
                .withBean(MaterialReadLeaseService.class,
                        () -> mock(MaterialReadLeaseService.class))
                .withBean(MaterialPageAccessPort.class,
                        () -> mock(MaterialPageAccessPort.class))
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    context.assertThat().hasSingleBean(DirectImageConversionExecutionModule.class);
                    context.assertThat().hasSingleBean(RequestSourceResolutionService.class);
                    context.assertThat().hasSingleBean(EvidenceReadLeaseCoordinator.class);
                });
    }

    @Test
    void directFallbacksBackOffWhenSharedSourceBeansAlreadyExist() {
        new ApplicationContextRunner()
                .withUserConfiguration(DirectImageConversionConfig.class)
                .withPropertyValues(
                        "app.material-lifecycle.enabled=true",
                        "app.material-visual-observation.enabled=true",
                        "app.material-direct-image-conversion.enabled=true")
                .withBean(VisualObservationModule.class,
                        () -> mock(VisualObservationModule.class))
                .withBean(CanvasCommitModule.class, () -> mock(CanvasCommitModule.class))
                .withBean(GroundedRunControlPort.class,
                        () -> mock(GroundedRunControlPort.class))
                .withBean(RequestSourceResolutionService.class,
                        () -> mock(RequestSourceResolutionService.class))
                .withBean(EvidenceReadLeaseCoordinator.class,
                        () -> mock(EvidenceReadLeaseCoordinator.class))
                .withBean(MaterialPageAccessPort.class,
                        () -> mock(MaterialPageAccessPort.class))
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    context.assertThat().hasSingleBean(RequestSourceResolutionService.class);
                    context.assertThat().hasSingleBean(EvidenceReadLeaseCoordinator.class);
                });
    }

    @Test
    void sharedVisualObservationIsWiredWithoutEnablingRag() {
        IChatService chat = mock(IChatService.class);
        when(chat.isAgentToolFree("agent-visual")).thenReturn(true);

        new ApplicationContextRunner()
                .withUserConfiguration(MaterialVisualObservationConfig.class)
                .withPropertyValues(
                        "app.material-lifecycle.enabled=true",
                        "app.material-visual-observation.enabled=true",
                        "app.material-rag.enabled=false",
                        "app.material-visual-observation.aws-region=ap-southeast-2",
                        "app.material-visual-observation.materials-bucket=test-materials",
                        "MATERIAL_VISUAL_OBSERVATION_AGENT_ID=agent-visual")
                .withBean(IChatService.class, () -> chat)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    context.assertThat().hasNotFailed();
                    context.assertThat().hasSingleBean(VisualObservationModule.class);
                });
    }
}
