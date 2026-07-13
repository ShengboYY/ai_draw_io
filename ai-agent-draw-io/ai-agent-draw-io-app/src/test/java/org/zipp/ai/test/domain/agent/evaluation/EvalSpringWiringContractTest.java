package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.intake.DeterministicCandidateSelectorService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalDraftService;
import org.zipp.ai.domain.agent.service.evaluation.intake.TraceToEvalIntakeService;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class EvalSpringWiringContractTest {

    @Test
    public void multiConstructorServicesDeclareOneProductionInjectionPoint() {
        // Clock-aware constructors are test seams; Spring must have one unambiguous production constructor.
        List<Class<?>> services = List.of(EvalCaseDryRunService.class, EvalCasePublisherService.class,
                EvalCaseReviewService.class, EvalCaseValidationService.class, EvalCaseWorkingCopyService.class,
                EvalDatasetService.class, EvalRunOrchestrator.class, EvalRunQueryService.class,
                EvalCanaryOperationsService.class, DeterministicCandidateSelectorService.class,
                TraceToEvalDraftService.class, TraceToEvalIntakeService.class);

        services.forEach(service -> assertEquals(service.getSimpleName(), 1,
                java.util.Arrays.stream(service.getDeclaredConstructors())
                        .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)).count()));
    }
}
