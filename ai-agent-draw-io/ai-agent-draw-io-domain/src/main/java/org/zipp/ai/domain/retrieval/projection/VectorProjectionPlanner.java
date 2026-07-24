package org.zipp.ai.domain.retrieval.projection;

import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Creates deterministic provider-bounded batches without embedding lexical-only chunks. */
public final class VectorProjectionPlanner {
    private final int maximumBatchSize;
    private final int maximumBatchUtf8Bytes;

    public VectorProjectionPlanner(int maximumBatchSize, int maximumBatchUtf8Bytes) {
        if (maximumBatchSize < 1 || maximumBatchSize > 96 || maximumBatchUtf8Bytes < 1) {
            throw new IllegalArgumentException("vector batch limits are invalid");
        }
        this.maximumBatchSize = maximumBatchSize;
        this.maximumBatchUtf8Bytes = maximumBatchUtf8Bytes;
    }

    public VectorProjectionPlan plan(RetrievalProjectionManifest manifest, VectorGenerationProfile profile) {
        return plan(manifest, profile, true);
    }

    /** Temporary Conversation files keep lexical projections but must not create long-lived vectors. */
    public VectorProjectionPlan plan(RetrievalProjectionManifest manifest, VectorGenerationProfile profile,
                                     boolean durableIndexEligible) {
        var source = java.util.Objects.requireNonNull(manifest, "manifest");
        var generation = java.util.Objects.requireNonNull(profile, "profile");
        String generationId = generation.generationId();
        List<VectorProjectionTarget> targets = source.chunks().stream()
                .filter(ignored -> durableIndexEligible)
                .filter(chunk -> chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL)
                .sorted(Comparator.comparingInt(RetrievalChunkProjection::structuralOrdinal)
                        .thenComparing(RetrievalChunkProjection::chunkId))
                .map(chunk -> target(chunk, generationId, generation)).toList();
        List<VectorBatchPlan> batches = batches(generationId, targets);
        String planFingerprint = VectorGenerationProfile.sha256(source.revisionId() + ":" + generationId + ":"
                + generation.generationFingerprint() + ":" + targets.stream()
                .map(VectorProjectionTarget::projectionFingerprint).toList());
        return new VectorProjectionPlan(source.revisionId(), source.versionId(), generationId,
                planFingerprint, generation, targets, batches);
    }

    private VectorProjectionTarget target(RetrievalChunkProjection chunk, String generationId,
                                          VectorGenerationProfile profile) {
        String fingerprint = VectorGenerationProfile.sha256(chunk.retrievalTextSha256() + ":" + generationId
                + ":" + profile.tokenizerFingerprint() + ":" + profile.embeddingModelFingerprint()
                + ":" + EmbeddingInputType.PASSAGE.name());
        return new VectorProjectionTarget(chunk.chunkId(), "rc_" + chunk.chunkId() + "_ig" + generationId,
                chunk.retrievalText(), chunk.retrievalTextSha256(), chunk.chunkType(), chunk.modality(),
                chunk.pageId(), chunk.languagePrimary(), fingerprint);
    }

    private List<VectorBatchPlan> batches(String generationId, List<VectorProjectionTarget> targets) {
        List<VectorBatchPlan> result = new ArrayList<>();
        List<VectorProjectionTarget> current = new ArrayList<>();
        int currentBytes = 0;
        for (VectorProjectionTarget target : targets) {
            int bytes = target.retrievalText().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maximumBatchUtf8Bytes) {
                throw new IllegalArgumentException("one retrieval chunk exceeds the embedding payload budget");
            }
            if (!current.isEmpty() && (current.size() == maximumBatchSize
                    || currentBytes + bytes > maximumBatchUtf8Bytes)) {
                result.add(batch(generationId, result.size(), current));
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(target);
            currentBytes += bytes;
        }
        if (!current.isEmpty()) result.add(batch(generationId, result.size(), current));
        return List.copyOf(result);
    }

    private VectorBatchPlan batch(String generationId, int batchNo, List<VectorProjectionTarget> projections) {
        String workKey = "ig:" + generationId + ":batch:" + String.format("%04d", batchNo);
        String fingerprint = VectorGenerationProfile.sha256(workKey + ":" + projections.stream()
                .map(VectorProjectionTarget::projectionFingerprint).toList());
        return new VectorBatchPlan(batchNo, workKey, fingerprint, projections);
    }
}
