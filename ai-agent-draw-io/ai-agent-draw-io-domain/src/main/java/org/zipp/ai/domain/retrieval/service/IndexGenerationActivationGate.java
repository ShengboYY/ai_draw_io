package org.zipp.ai.domain.retrieval.service;

import org.zipp.ai.domain.retrieval.model.valobj.GenerationActivationPolicy;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationBackfillStatus;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationShadowReport;
import org.zipp.ai.domain.retrieval.model.valobj.IndexGenerationState;

import java.util.Objects;

/** Domain gate that prevents a partial, unsafe, or regressed generation from becoming ACTIVE. */
public final class IndexGenerationActivationGate {

    public void verify(GenerationBackfillStatus status, GenerationShadowReport report,
                       GenerationActivationPolicy policy) {
        GenerationBackfillStatus candidate = Objects.requireNonNull(status, "status");
        GenerationShadowReport evidence = Objects.requireNonNull(report, "report");
        GenerationActivationPolicy thresholds = Objects.requireNonNull(policy, "policy");
        if (candidate.state() != IndexGenerationState.SHADOW || !candidate.complete()) {
            throw new IllegalStateException("generation backfill is not complete in shadow mode");
        }
        if (!candidate.generationId().equals(evidence.generationId())
                || !Objects.equals(candidate.activeGenerationId(), evidence.baselineGenerationId())
                || candidate.targetGeneration() != evidence.targetGeneration()
                || !thresholds.policyFingerprint().equals(evidence.policyFingerprint())) {
            throw new IllegalStateException("shadow report identity is stale");
        }
        if (evidence.sampleCount() < thresholds.minimumSamples()
                || evidence.authorizationMismatchCount() != 0
                || evidence.candidateRecallAt40() - evidence.baselineRecallAt40()
                        < thresholds.minimumRecallDelta()
                || evidence.candidateNdcgAt16() - evidence.baselineNdcgAt16()
                        < thresholds.minimumNdcgDelta()
                || (double) evidence.candidateP95LatencyMs() / evidence.baselineP95LatencyMs()
                        > thresholds.maximumP95LatencyRatio()) {
            throw new IllegalStateException("shadow report does not satisfy the activation policy");
        }
    }
}
