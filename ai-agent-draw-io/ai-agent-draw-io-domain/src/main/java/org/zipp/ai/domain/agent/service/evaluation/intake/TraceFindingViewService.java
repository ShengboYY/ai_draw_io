package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingContext;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingFilter;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceFindingView;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceRecommendation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Builds the read-only Finding projection without introducing a second Inbox state machine. */
@Service
public class TraceFindingViewService {
    private final ITraceToEvalStore candidates;

    public TraceFindingViewService(ITraceToEvalStore candidates) {
        this.candidates = candidates;
    }

    public List<TraceFindingView> list(String status, String risk, String analyzer, String routeType,
                                       String agentId, String sourceRunId, Instant discoveredFrom,
                                       Instant discoveredTo, Long minLatencyMs, Long maxLatencyMs,
                                       int requestedLimit, int offset) {
        org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus parsed = StringUtils.isBlank(status)
                ? null : org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus.valueOf(StringUtils.upperCase(status));
        String normalizedAnalyzer = StringUtils.upperCase(StringUtils.trimToEmpty(analyzer));
        if (StringUtils.isNotBlank(normalizedAnalyzer) && !List.of("DETERMINISTIC", "LLM", "VLM").contains(normalizedAnalyzer)) {
            throw new IllegalArgumentException("unsupported analyzer filter");
        }
        TraceFindingFilter filter = new TraceFindingFilter(parsed, StringUtils.trimToNull(StringUtils.lowerCase(risk)),
                StringUtils.trimToNull(normalizedAnalyzer), StringUtils.trimToNull(routeType), StringUtils.trimToNull(agentId),
                StringUtils.trimToNull(sourceRunId), discoveredFrom, discoveredTo, minLatencyMs, maxLatencyMs,
                Math.max(1, Math.min(requestedLimit, 200)), Math.max(0, offset));
        return candidates.listFindingCandidates(filter).stream().map(this::project)
                .toList();
    }

    public TraceFindingView find(String candidateId) {
        return project(candidates.findFindingCandidate(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("finding not found")));
    }

    private TraceFindingView project(TraceFindingCandidate row) {
        EvalCaseCandidate candidate = row.candidate();
        TraceFindingContext context = row.context();
        String analyzer = analyzer(candidate);
        List<String> analysisEvidence = candidate.getModelEvidence() == null || candidate.getModelEvidence().isEmpty()
                ? java.util.stream.Stream.of(candidate.getRuleId(), candidate.getEvidenceSummary())
                    .filter(StringUtils::isNotBlank).toList()
                : candidate.getModelEvidence();
        List<String> evidenceRefs = new ArrayList<>();
        evidenceRefs.add("trace://run/" + candidate.getSourceRunId());
        if (StringUtils.isNotBlank(candidate.getSourceSpanId())) {
            evidenceRefs.add("trace://run/" + candidate.getSourceRunId() + "/span/" + candidate.getSourceSpanId());
        }
        return new TraceFindingView(candidate.getId(), candidate.getSourceRunId(),
                context.routeType(), context.sourceAgentId(), context.sourceLatencyMs(), analyzer,
                "DETERMINISTIC".equals(analyzer) ? candidate.getPolicyVersion() : candidate.getModelVersion(),
                candidate.getFailureFamily(), candidate.getRisk(), candidate.getModelConfidence(),
                candidate.getEvidenceSummary(), analysisEvidence, evidenceRefs,
                recommendation(candidate.getFailureFamily(), candidate.getEvidenceSummary(), candidate.getModelConfidence()),
                candidate.getStatus(), candidate.getDiscoveredAt(), context.reviewedBy(), context.reviewedAt());
    }

    private String analyzer(EvalCaseCandidate candidate) {
        if (StringUtils.containsIgnoreCase(candidate.getRuleId(), "visual")) return "VLM";
        if ("MODEL_DETECTED".equals(candidate.getDetectionSource())) return "LLM";
        return "DETERMINISTIC";
    }

    private TraceRecommendation recommendation(String family, String evidence, Double confidence) {
        if (StringUtils.containsIgnoreCase(family, "latency")) return recommendation("tool", evidence,
                "Lower user-visible latency", "Profile the slow phase and compare a bounded optimization experiment.", confidence);
        if (StringUtils.containsIgnoreCase(family, "visual")) return recommendation("UI", evidence,
                "Improve diagram readability", "Reproduce with a synthetic diagram and test one layout/style change.", confidence);
        if (StringUtils.containsIgnoreCase(family, "semantic")) return recommendation("prompt", evidence,
                "Align the response with the request", "Compare router, prompt and tool trajectory on a synthetic Case.", confidence);
        if (StringUtils.containsIgnoreCase(family, "artifact")) return recommendation("XML", evidence,
                "Make the final canvas load reliably", "Inspect mutation and canvas persistence before changing model behavior.", confidence);
        return recommendation("agent", evidence, "Prevent recurrence",
                "Reproduce the evidence with a synthetic Case before changing the Agent.", confidence);
    }

    private TraceRecommendation recommendation(String layer, String evidence, String impact, String experiment, Double confidence) {
        return new TraceRecommendation(layer, StringUtils.left(evidence, 512), impact, experiment, 1, confidence);
    }
}
