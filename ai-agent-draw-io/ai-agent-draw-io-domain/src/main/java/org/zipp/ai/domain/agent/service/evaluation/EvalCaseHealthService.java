package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalSampleResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseHealthRecord;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Produces and optionally persists non-sensitive case-health feedback for dataset maintenance. */
public class EvalCaseHealthService {
    private final ITraceToEvalStore store;
    private final Clock clock;

    public EvalCaseHealthService(ITraceToEvalStore store) { this(store, Clock.systemUTC()); }
    EvalCaseHealthService(ITraceToEvalStore store, Clock clock) { this.store = store; this.clock = clock; }

    public EvalCaseHealthRecord assess(String caseId, String caseVersion, Boolean baselineReproduced,
                                       Instant caseUpdatedAt, List<EvalSampleResult> samples) {
        List<EvalSampleResult> eligible = samples == null ? List.of() : samples.stream().filter(sample ->
                sample.getStatus() == EvalHarnessResult.Status.PASS || sample.getStatus() == EvalHarnessResult.Status.FAIL).toList();
        long passes = eligible.stream().filter(EvalSampleResult::isPassed).count();
        double probability = eligible.isEmpty() ? 0D : passes / (double) eligible.size();
        String status;
        if (Boolean.FALSE.equals(baselineReproduced)) status = "BROKEN_BASELINE";
        else if (eligible.isEmpty()) status = "UNSCORABLE";
        else if (probability > 0D && probability < 1D) status = "FLAKY";
        else if (probability == 1D && eligible.size() >= 5) status = "ALWAYS_PASS_REVIEW";
        else if (caseUpdatedAt != null && caseUpdatedAt.isBefore(clock.instant().minus(Duration.ofDays(180)))) status = "STALE_REVIEW";
        else status = "HEALTHY";
        EvalCaseHealthRecord record = EvalCaseHealthRecord.builder().caseId(caseId).caseVersion(caseVersion)
                .baselineReproduced(baselineReproduced).healthStatus(status)
                .summary("eligible=" + eligible.size() + ", success_probability=" + probability)
                .updatedAt(clock.instant()).build();
        if (store != null) store.upsertCaseHealth(record);
        return record;
    }
}
