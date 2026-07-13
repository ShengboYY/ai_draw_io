package org.zipp.ai.domain.agent.service.evaluation.controlplane;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalSampleResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalCaseVersion;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalEpisode;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalEpisodeStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRun;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.EvalRunStatus;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseHealthRecord;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseHealthService;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceToEvalStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a non-sensitive maintenance queue from immutable Eval results; it never reads production traces. */
@Service
public class EvalCaseHealthOperationsService {
    private final IEvalRunStore runs;
    private final IEvalCaseVersionStore cases;
    private final ITraceToEvalStore health;
    public EvalCaseHealthOperationsService(IEvalRunStore runs, IEvalCaseVersionStore cases, ITraceToEvalStore health) {
        this.runs = runs; this.cases = cases; this.health = health;
    }

    public List<EvalCaseHealthRecord> refresh(int requestedRunLimit) {
        int limit = Math.max(1, Math.min(requestedRunLimit, 500));
        List<EvalRun> completed = runs.listRuns(limit, 0).stream()
                .filter(run -> run.getStatus() == EvalRunStatus.COMPLETED).toList();
        Map<CaseIdentity, List<EvalSampleResult>> samples = new LinkedHashMap<>();
        Map<CaseIdentity, Boolean> baselineReproduced = baselineEvidence(completed);
        java.util.Set<String> baselineRunIds = completed.stream().map(EvalRun::getBaselineRef)
                .filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.toSet());
        for (EvalRun run : completed.stream().filter(value -> !baselineRunIds.contains(value.getId())).toList()) for (EvalEpisode episode : runs.listEpisodes(run.getId())) {
            samples.computeIfAbsent(key(episode), ignored -> new ArrayList<>()).add(sample(episode));
        }
        EvalCaseHealthService assessor = new EvalCaseHealthService(health);
        List<EvalCaseHealthRecord> records = new ArrayList<>();
        samples.forEach((identity, values) -> {
            EvalCaseVersion version = cases.find(identity.caseId(), identity.caseVersion()).orElse(null);
            records.add(assessor.assess(identity.caseId(), identity.caseVersion(), baselineReproduced.get(identity),
                    version == null ? null : version.getPublishedAt(), values));
        });
        return records;
    }

    public List<EvalCaseHealthRecord> list(String status, int requestedLimit) {
        String normalized = StringUtils.isBlank(status) ? null : StringUtils.upperCase(status.trim());
        if (normalized != null && !List.of("HEALTHY", "FLAKY", "ALWAYS_PASS_REVIEW", "UNSCORABLE", "STALE_REVIEW", "BROKEN_BASELINE").contains(normalized)) {
            throw new IllegalArgumentException("unsupported Case Health status");
        }
        return health.listCaseHealth(normalized, Math.max(1, Math.min(requestedLimit, 200)));
    }

    private Map<CaseIdentity, Boolean> baselineEvidence(List<EvalRun> completed) {
        Map<CaseIdentity, List<EvalEpisode>> evidence = new LinkedHashMap<>();
        for (EvalRun candidate : completed) {
            if (StringUtils.isBlank(candidate.getBaselineRef())) continue;
            runs.findRun(candidate.getBaselineRef()).ifPresent(baseline -> runs.listEpisodes(baseline.getId())
                    .forEach(episode -> evidence.computeIfAbsent(key(episode), ignored -> new ArrayList<>()).add(episode)));
        }
        Map<CaseIdentity, Boolean> result = new LinkedHashMap<>();
        evidence.forEach((key, episodes) -> {
            List<EvalEpisode> eligible = episodes.stream().filter(value -> value.getStatus() == EvalEpisodeStatus.PASS || value.getStatus() == EvalEpisodeStatus.FAIL).toList();
            if (!eligible.isEmpty()) result.put(key, eligible.stream().anyMatch(value -> value.getStatus() == EvalEpisodeStatus.FAIL));
        });
        return result;
    }

    private EvalSampleResult sample(EvalEpisode value) {
        return EvalSampleResult.builder().caseId(value.getCaseId()).repetition(value.getRepetition())
                .status(EvalHarnessResult.Status.valueOf(value.getStatus().name())).passed(value.getStatus() == EvalEpisodeStatus.PASS)
                .latencyMs(value.getLatencyMs()).inputTokens(value.getInputTokens()).outputTokens(value.getOutputTokens())
                .estimatedCost(value.getEstimatedCost()).errorClass(value.getErrorClass()).build();
    }
    private CaseIdentity key(EvalEpisode value) { return new CaseIdentity(value.getCaseId(), value.getCaseVersion()); }

    private record CaseIdentity(String caseId, String caseVersion) { }
}
