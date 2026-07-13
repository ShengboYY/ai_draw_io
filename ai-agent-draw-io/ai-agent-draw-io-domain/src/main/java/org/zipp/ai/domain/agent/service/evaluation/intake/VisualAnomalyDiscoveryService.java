package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseCandidate;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCandidateStatus;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IDiagramImageRenderer;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualMinerCallExecutor;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit, one-run visual discovery. Production pixels stay in memory and only sanitized evidence becomes a Candidate. */
@Service
public class VisualAnomalyDiscoveryService {
    private static final double MIN_CONFIDENCE = 0.5D;
    private static final java.util.Set<String> EVIDENCE_CODES = java.util.Set.of("TEXT_TOO_SMALL", "LOW_CONTRAST", "WEAK_HIERARCHY",
            "EXCESSIVE_DENSITY", "INCONSISTENT_GROUPING", "EDGE_TRACE_DIFFICULT", "STYLE_INCOHERENT");
    private static final java.util.Set<String> RECONSTRUCTION_CODES = java.util.Set.of("SYNTHETIC_SMALL_LABEL_FLOW", "SYNTHETIC_LOW_CONTRAST_FLOW",
            "SYNTHETIC_FLAT_HIERARCHY", "SYNTHETIC_DENSE_GRAPH", "SYNTHETIC_INCONSISTENT_GROUPS", "SYNTHETIC_TANGLED_EDGES", "SYNTHETIC_INCOHERENT_STYLES");
    private final IAgentUsageTelemetryStore telemetry;
    private final ICanvasStateStore canvases;
    private final ITraceToEvalStore candidates;
    private final IDiagramImageRenderer renderer;
    private final IVisualAnomalyMiner miner;
    private final boolean enabled;
    private final boolean calibrationApproved;
    private final String calibratedVersion;
    private final int maxImageBytes;
    private final double estimatedCostPerAnalysisUsd;
    private final Duration timeout;
    private final IVisualMinerCallExecutor calls;
    private final Clock clock;

    @Autowired
    public VisualAnomalyDiscoveryService(IAgentUsageTelemetryStore telemetry, ICanvasStateStore canvases,
            ITraceToEvalStore candidates, IDiagramImageRenderer renderer, IVisualAnomalyMiner miner,
            IVisualMinerCallExecutor calls,
            @Value("${zipp.evaluation.visual-miner-enabled:false}") boolean enabled,
            @Value("${zipp.evaluation.visual-miner-calibration-approved:false}") boolean calibrationApproved,
            @Value("${zipp.evaluation.visual-miner-calibrated-version:unconfigured}") String calibratedVersion,
            @Value("${zipp.evaluation.visual-miner-max-image-bytes:2000000}") int maxImageBytes,
            @Value("${zipp.evaluation.visual-miner-estimated-cost-per-analysis-usd:0}") double estimatedCostPerAnalysisUsd,
            @Value("${zipp.evaluation.visual-miner-timeout-ms:30000}") long timeoutMs) {
        this(telemetry, canvases, candidates, renderer, miner, enabled, calibrationApproved, calibratedVersion,
                maxImageBytes, estimatedCostPerAnalysisUsd, Duration.ofMillis(Math.max(1L, timeoutMs)), calls, Clock.systemUTC());
    }

    public VisualAnomalyDiscoveryService(IAgentUsageTelemetryStore telemetry, ICanvasStateStore canvases,
            ITraceToEvalStore candidates, IDiagramImageRenderer renderer, IVisualAnomalyMiner miner,
            boolean enabled, boolean calibrationApproved, String calibratedVersion, int maxImageBytes, Clock clock) {
        this(telemetry, canvases, candidates, renderer, miner, enabled, calibrationApproved, calibratedVersion,
                maxImageBytes, 0D, Duration.ofSeconds(30), (adapter, input, timeout) -> adapter.analyze(input), clock);
    }

    private VisualAnomalyDiscoveryService(IAgentUsageTelemetryStore telemetry, ICanvasStateStore canvases,
            ITraceToEvalStore candidates, IDiagramImageRenderer renderer, IVisualAnomalyMiner miner,
            boolean enabled, boolean calibrationApproved, String calibratedVersion, int maxImageBytes,
            double estimatedCostPerAnalysisUsd, Duration timeout, IVisualMinerCallExecutor calls, Clock clock) {
        this.telemetry = telemetry; this.canvases = canvases; this.candidates = candidates; this.renderer = renderer; this.miner = miner;
        this.enabled = enabled; this.calibrationApproved = calibrationApproved; this.calibratedVersion = calibratedVersion;
        this.maxImageBytes = Math.max(1, maxImageBytes); this.clock = clock == null ? Clock.systemUTC() : clock;
        this.estimatedCostPerAnalysisUsd = Math.max(0D, estimatedCostPerAnalysisUsd); this.timeout = timeout; this.calls = calls;
    }

    public Result analyzeRun(String sourceRunId, String actor, boolean purposeConfirmed) {
        if (!purposeConfirmed) throw new IllegalArgumentException("visual discovery purpose confirmation is required");
        if (StringUtils.isBlank(sourceRunId) || StringUtils.isBlank(actor)) throw new IllegalArgumentException("sourceRunId and actor are required");
        String unavailable = readinessFailure(); if (unavailable != null) return new Result("UNAVAILABLE", unavailable, null, 0D, 0D);
        AgentRunDetail detail = telemetry.findRunDetail(sourceRunId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        if (detail.getRun() == null || StringUtils.isAnyBlank(detail.getRun().getUserId(), detail.getRun().getDiagramId())) throw new IllegalStateException("run has no diagram identity");
        CanvasState canvas = canvases.find(detail.getRun().getUserId(), detail.getRun().getDiagramId())
                .orElseThrow(() -> new IllegalStateException("final canvas is unavailable"));
        IDiagramImageRenderer.RenderedDiagram image = renderer.render(canvas.getCurrentXml());
        if (image.bytes().length > maxImageBytes) return new Result("UNAVAILABLE", "rendered_image_quota_exceeded", null, 0D, 0D);
        CanvasAnalysis analysis = new DefaultCanvasAnalyzer().analyze(canvas.getCurrentXml(), canvas.getDiagramType());
        List<String> analyzerEvidence = analysis.getIssues() == null ? List.of() : analysis.getIssues().stream()
                .map(issue -> issue.getType() + ":" + issue.getSeverity()).limit(20).toList();
        IVisualAnomalyMiner.Finding finding = calls.execute(miner, new IVisualAnomalyMiner.Input(image, analyzerEvidence, canvas.getDiagramType()), timeout);
        if (finding == null || !finding.potentialIssue() || !finding.requiresHumanReview() || finding.confidence() < MIN_CONFIDENCE) return new Result("NO_FINDING", null, null, finding == null ? 0D : finding.confidence(), estimatedCostPerAnalysisUsd);
        List<String> evidence = safeEvidence(finding);
        String family = "visual_" + StringUtils.lowerCase(finding.issueFamily());
        Optional<EvalCaseCandidate> existing = candidates.findCandidateBySourceRunAndFailureFamily(sourceRunId, family);
        if (existing.isPresent()) { candidates.mergeCandidateModelEvidence(existing.get().getId(), miner.version(), finding.confidence(), evidence, String.join("; ", evidence)); return new Result("MERGED", null, existing.get().getId(), finding.confidence(), estimatedCostPerAnalysisUsd); }
        EvalCaseCandidate candidate = EvalCaseCandidate.builder().id("ecc_" + UUID.randomUUID()).sourceRunId(sourceRunId)
                .sourceAgentId(detail.getRun().getAgentId()).sourcePhase("visual_analysis").failureFamily(family).ruleId("visual_miner")
                .evidenceSummary(StringUtils.left(String.join("; ", evidence), 1024)).risk(finding.confidence() < 0.8D ? "medium" : normalizedRisk(finding.suggestedRisk()))
                .discoveredAt(clock.instant()).policyVersion("visual-manual-sampling-v1").status(EvalCandidateStatus.DETECTED)
                .createdBy(actor).detectionSource("MODEL_DETECTED").modelVersion(miner.version())
                .modelConfidence(finding.confidence()).modelEvidence(evidence).build();
        candidates.insertCandidate(candidate); return new Result("CANDIDATE_CREATED", null, candidate.getId(), finding.confidence(), estimatedCostPerAnalysisUsd);
    }

    public String analyzerVersion() { return miner.version(); }
    public double estimatedCostPerAnalysisUsd() { return estimatedCostPerAnalysisUsd; }

    private List<String> safeEvidence(IVisualAnomalyMiner.Finding finding) {
        if (finding.evidence() == null || finding.evidence().stream().anyMatch(value -> !EVIDENCE_CODES.contains(value))
                || !RECONSTRUCTION_CODES.contains(finding.syntheticReconstructionSuggestion())) {
            throw new IllegalArgumentException("visual evidence is not from the approved code set");
        }
        List<String> values = new ArrayList<>(finding.evidence().stream().map(value -> "Visual issue code: " + value).toList());
        values.add("Synthetic reconstruction: " + finding.syntheticReconstructionSuggestion());
        EvalDraftSanitizer.Result sanitized = new EvalDraftSanitizer().sanitize(values);
        if (!sanitized.safeForModel() || !sanitized.removedCategories().isEmpty()) throw new IllegalArgumentException("visual evidence contains restricted data");
        return values.stream().map(value -> StringUtils.left(value, 300)).limit(8).toList();
    }

    private String readinessFailure() { if (!enabled) return "visual_miner_disabled"; if (!calibrationApproved) return "visual_calibration_not_approved"; if (StringUtils.isBlank(calibratedVersion) || !calibratedVersion.equals(miner.version())) return "visual_calibrated_version_mismatch"; return null; }
    private String normalizedRisk(String value) { String risk = StringUtils.lowerCase(StringUtils.trimToEmpty(value)); return List.of("critical", "high", "medium", "low").contains(risk) ? risk : "medium"; }
    public record Result(String status, String reason, String candidateId, double confidence, double estimatedCostUsd) { }
}
