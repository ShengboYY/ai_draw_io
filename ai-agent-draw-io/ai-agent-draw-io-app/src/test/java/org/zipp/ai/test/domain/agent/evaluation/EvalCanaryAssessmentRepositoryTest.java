package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCanaryAssessment;
import org.zipp.ai.domain.agent.service.evaluation.EvalCanaryService;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane.EvalCanaryAssessmentRepository;
import org.zipp.ai.infrastructure.dao.IEvalCanaryAssessmentMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.EvalCanaryAssessmentPO;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class EvalCanaryAssessmentRepositoryTest {

    @Test
    public void aggregateAssessmentRoundTripsReasonsAndMetrics() {
        FakeMapper mapper = new FakeMapper();
        EvalCanaryAssessmentRepository repository = new EvalCanaryAssessmentRepository(mapper);
        EvalCanaryAssessment value = EvalCanaryAssessment.builder().id("eca-1").evalRunId("run-1")
                .deploymentRef("deploy-1").policyVersion("policy-v1").outcome(EvalCanaryService.Outcome.HALT_RECOMMENDED)
                .reasons(List.of("critical findings observed in canary")).baselineRequests(1000)
                .canaryRequests(100).canaryFailures(1).criticalFindings(1).infrastructureErrors(0)
                .p95LatencyMs(120D).averageCost(0.01D).createdBy("release-owner")
                .createdAt(Instant.parse("2026-07-13T00:00:00Z")).build();

        repository.insert(value);
        EvalCanaryAssessment restored = repository.list("run-1", 10).get(0);

        assertEquals(value, restored);
        assertEquals("[\"critical findings observed in canary\"]", mapper.value.getReasonsJson());
    }

    private static final class FakeMapper implements IEvalCanaryAssessmentMapper {
        private EvalCanaryAssessmentPO value;
        @Override public int insert(EvalCanaryAssessmentPO value) { this.value = value; return 1; }
        @Override public List<EvalCanaryAssessmentPO> list(String run, int limit) { return List.of(value); }
    }
}
