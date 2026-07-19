package org.zipp.ai.ingestion.worker;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.ingestion.port.ProcessingQueuePort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.EmbeddingPort;
import org.zipp.ai.domain.retrieval.port.EmbeddingCachePort;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;
import org.zipp.ai.domain.retrieval.port.RetryableRetrievalException;
import org.zipp.ai.domain.retrieval.port.TenantKeyPort;
import org.zipp.ai.domain.retrieval.port.VectorProjectionWorkPort;
import org.zipp.ai.domain.retrieval.projection.*;
import org.zipp.ai.ingestion.worker.document.RevisionPageCodec;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable coordinator for generation-scoped embedding, Pinecone upsert, and manifest creation. */
public final class VectorProjectionJobHandler {
    private static final long MAXIMUM_ARTIFACT_BYTES = 64L * 1024 * 1024;
    private static final Duration HEARTBEAT_EXTENSION = Duration.ofMinutes(30);
    private static final String JSON_GZIP = "application/json+gzip";

    private final VectorProjectionWorkPort work;
    private final RevisionArtifactPort artifacts;
    private final EmbeddingPort embedding;
    private final EmbeddingCachePort embeddingCache;
    private final RetrievalVectorIndex vectorIndex;
    private final TenantKeyPort tenantKeys;
    private final VectorProjectionPlanner planner;
    private final RevisionPageCodec codec;
    private final VectorGenerationProfile profile;
    private final ProcessingQueuePort queue;
    private final Clock clock;

    public VectorProjectionJobHandler(VectorProjectionWorkPort work, RevisionArtifactPort artifacts,
                                      EmbeddingPort embedding, EmbeddingCachePort embeddingCache,
                                      RetrievalVectorIndex vectorIndex,
                                      TenantKeyPort tenantKeys, VectorProjectionPlanner planner,
                                      RevisionPageCodec codec, VectorGenerationProfile profile,
                                      ProcessingQueuePort queue, Clock clock) {
        this.work = Objects.requireNonNull(work, "work");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.embeddingCache = Objects.requireNonNull(embeddingCache, "embeddingCache");
        this.vectorIndex = Objects.requireNonNull(vectorIndex, "vectorIndex");
        this.tenantKeys = Objects.requireNonNull(tenantKeys, "tenantKeys");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JobOutcome handle(ProcessingJobLease lease) {
        ProcessingJob job = Objects.requireNonNull(lease, "lease").job();
        String revisionId = job.target().revisionId();
        if (revisionId == null) return JobOutcome.permanent("UNSUPPORTED_VECTOR_TARGET");
        try {
            return switch (job.stage()) {
                case BUILD_LEXICAL_PROJECTION -> coordinate(revisionId, lease);
                case EMBED_CHUNK_BATCHES -> embed(revisionId, lease);
                case UPSERT_VECTOR_BATCHES -> upsert(revisionId, lease);
                case VERIFY_PROJECTION_MANIFEST -> manifest(revisionId, lease);
                default -> JobOutcome.permanent("UNSUPPORTED_VECTOR_STAGE");
            };
        } catch (IllegalArgumentException e) {
            return JobOutcome.permanent("INVALID_VECTOR_PROJECTION");
        } catch (RetryableRetrievalException e) {
            return JobOutcome.transientFailure(
                    UploadErrorCode.TRANSIENT_DEPENDENCY.name(), e.retryAfter());
        } catch (RuntimeException e) {
            // Vector dependencies are retried by their own jobs and never enter the plain-text drawing path.
            return JobOutcome.transientFailure(UploadErrorCode.TRANSIENT_DEPENDENCY.name());
        }
    }

    private JobOutcome coordinate(String revisionId, ProcessingJobLease lease) {
        RevisionProjectionContext context = work.findCoordinatorWork(revisionId, fence(lease)).orElse(null);
        if (context == null) return JobOutcome.succeeded();
        String expected = ProcessingStageFingerprintPolicy.lexicalProjectionInput(
                context.retrievalManifestArtifact().contentSha256(), context.processingFingerprint());
        if (!expected.equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        if (!heartbeat(lease)) return staleFence();
        var retrieval = readRetrievalManifest(context);
        VectorProjectionPlan plan = planner.plan(retrieval, profile);
        List<ProcessingJob> successors = plan.batches().isEmpty()
                ? List.of(nextJob(revisionId, ProcessingJobStage.VERIFY_PROJECTION_MANIFEST,
                        VectorManifestWork.verificationWorkKey(profile.generationId()),
                        VectorManifestWork.verificationInputFingerprint(
                                revisionId, profile.generationId())))
                : plan.batches().stream().map(batch -> nextJob(revisionId,
                        ProcessingJobStage.EMBED_CHUNK_BATCHES, batch.workKey(), batch.inputFingerprint())).toList();
        return work.commitCoordinator(context, plan, successors, fence(lease))
                ? JobOutcome.succeeded() : staleFence();
    }

    private JobOutcome embed(String revisionId, ProcessingJobLease lease) {
        VectorEmbeddingWork source = work.findEmbeddingWork(
                revisionId, lease.job().workKey(), fence(lease)).orElse(null);
        if (source == null) return JobOutcome.succeeded();
        verifyProfile(source.profile());
        if (!source.batchInputFingerprint().equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        if (!heartbeat(lease)) return staleFence();
        VectorProjectionPlan plan = planner.plan(readRetrievalManifest(source.context()), source.profile());
        VectorBatchPlan batch = plan.batches().stream().filter(candidate -> candidate.batchNo() == source.batchNo())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("embedding batch is not in the plan"));
        if (!source.workKey().equals(batch.workKey())
                || !source.batchInputFingerprint().equals(batch.inputFingerprint())) {
            throw new IllegalArgumentException("embedding batch identity does not match its manifest");
        }
        List<VectorEmbeddingValue> embeddings = cachedEmbeddings(source, batch);
        VectorBatchPayload payload = new VectorBatchPayload("vector-batch-v1", revisionId,
                source.profile().generationId(), source.batchNo(), source.batchInputFingerprint(), embeddings);
        StoredArtifact artifact = artifacts.putImmutable(batchPrefix(revisionId, source.profile().generationId(),
                source.batchNo()) + ".vectors.json.gz", codec.encode(payload), JSON_GZIP);
        VectorBatchArtifactResult result = new VectorBatchArtifactResult(payload, artifact);
        ProcessingJob successor = nextJob(revisionId, ProcessingJobStage.UPSERT_VECTOR_BATCHES,
                source.workKey(), VectorUpsertWork.upsertInputFingerprint(source.workKey(),
                        source.batchInputFingerprint(), artifact.contentSha256()));
        return work.commitEmbedding(source, result, successor, fence(lease))
                ? JobOutcome.succeeded() : staleFence();
    }

    private JobOutcome upsert(String revisionId, ProcessingJobLease lease) {
        VectorUpsertWork source = work.findUpsertWork(
                revisionId, lease.job().workKey(), fence(lease)).orElse(null);
        if (source == null) return JobOutcome.succeeded();
        verifyProfile(source.profile());
        if (!source.upsertInputFingerprint().equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        if (!heartbeat(lease)) return staleFence();
        VectorBatchPayload payload = codec.decodeVectorBatchPayload(
                artifacts.read(source.vectorArtifact(), MAXIMUM_ARTIFACT_BYTES), MAXIMUM_ARTIFACT_BYTES);
        validatePayload(source, payload);
        Map<String, VectorEmbeddingValue> valuesByChunk = new HashMap<>();
        payload.embeddings().forEach(value -> valuesByChunk.put(value.chunkId(), value));
        String tenantKey = tenantKeys.opaqueKey(source.context().ownerType(), source.context().ownerKey());
        List<VectorProjection> projections = source.projections().stream().map(metadata -> {
            VectorEmbeddingValue value = valuesByChunk.get(metadata.chunkId());
            if (value == null || !metadata.vectorId().equals(value.vectorId())
                    || !metadata.projectionFingerprint().equals(value.projectionFingerprint())) {
                throw new IllegalArgumentException("vector payload does not match its authoritative projection");
            }
            Map<String, Object> pineconeMetadata = new HashMap<>();
            pineconeMetadata.put("tenant_key", tenantKey);
            pineconeMetadata.put("material_id", source.context().materialId());
            pineconeMetadata.put("version_id", source.context().versionId());
            pineconeMetadata.put("revision_id", source.context().revisionId());
            pineconeMetadata.put("retrieval_chunk_id", metadata.chunkId());
            pineconeMetadata.put("chunk_type", metadata.chunkType().name());
            pineconeMetadata.put("modality", metadata.modality().name());
            if (metadata.pageNo() != null) pineconeMetadata.put("page_no", metadata.pageNo());
            pineconeMetadata.put("language", metadata.language());
            pineconeMetadata.put("index_generation_id", source.profile().generationId());
            return new VectorProjection(metadata.chunkId(), source.profile().generationId(), metadata.vectorId(),
                    value.values(), pineconeMetadata);
        }).toList();
        vectorIndex.upsert(projections);
        String verificationWorkKey = VectorManifestWork.verificationWorkKey(
                source.profile().generationId());
        String verificationFingerprint = VectorManifestWork.verificationInputFingerprint(
                revisionId, source.profile().generationId());
        ProcessingJob verifier = nextJob(revisionId, ProcessingJobStage.VERIFY_PROJECTION_MANIFEST,
                verificationWorkKey, verificationFingerprint);
        return work.commitUpsert(source, payload, verifier, fence(lease))
                ? JobOutcome.succeeded() : staleFence();
    }

    private JobOutcome manifest(String revisionId, ProcessingJobLease lease) {
        VectorManifestWork source = work.findManifestWork(
                revisionId, lease.job().workKey(), fence(lease)).orElse(null);
        if (source == null) return JobOutcome.succeeded();
        verifyProfile(source.profile());
        if (!source.verificationInputFingerprint().equals(lease.job().inputFingerprint())) {
            return JobOutcome.permanent("STALE_PROCESSING_FINGERPRINT");
        }
        if (!heartbeat(lease)) return staleFence();
        VectorProjectionManifest manifest = VectorProjectionManifest.create(revisionId,
                source.context().versionId(), source.profile(), source.projectionRole(), source.entries());
        StoredArtifact artifact = artifacts.putImmutable("revisions/" + revisionId + "/projections/"
                + source.profile().generationId() + "/projection-manifest.json.gz",
                codec.encode(manifest), JSON_GZIP);
        VectorProjectionManifestResult result = new VectorProjectionManifestResult(manifest, artifact);
        ProcessingJob successor = nextJob(revisionId, ProcessingJobStage.PUBLISH_REVISION,
                "ig:" + source.profile().generationId(), VectorGenerationProfile.sha256(
                        artifact.contentSha256() + ":" + manifest.manifestHash() + ":PUBLISH_REVISION"));
        return work.commitManifest(source, result, successor, fence(lease))
                ? JobOutcome.succeeded() : staleFence();
    }

    private RetrievalProjectionManifest readRetrievalManifest(RevisionProjectionContext context) {
        var manifest = codec.decodeRetrievalProjectionManifest(
                artifacts.read(context.retrievalManifestArtifact(), MAXIMUM_ARTIFACT_BYTES),
                MAXIMUM_ARTIFACT_BYTES);
        if (!context.revisionId().equals(manifest.revisionId())
                || !context.versionId().equals(manifest.versionId())) {
            throw new IllegalArgumentException("retrieval manifest does not belong to the projection context");
        }
        return manifest;
    }

    private void validatePayload(VectorUpsertWork source, VectorBatchPayload payload) {
        if (!source.context().revisionId().equals(payload.revisionId())
                || !source.profile().generationId().equals(payload.generationId())
                || source.batchNo() != payload.batchNo()
                || !source.batchInputFingerprint().equals(payload.batchInputFingerprint())
                || source.projections().size() != payload.embeddings().size()) {
            throw new IllegalArgumentException("vector payload identity is stale");
        }
    }

    private void verifyProfile(VectorGenerationProfile persisted) {
        if (!profile.generationFingerprint().equals(persisted.generationFingerprint())) {
            throw new IllegalArgumentException("vector generation profile is unavailable");
        }
    }

    private List<VectorEmbeddingValue> cachedEmbeddings(VectorEmbeddingWork source, VectorBatchPlan batch) {
        String tenantKey = tenantKeys.opaqueKey(
                source.context().ownerType(), source.context().ownerKey());
        List<VectorEmbeddingValue> result = new ArrayList<>(
                Collections.nCopies(batch.projections().size(), null));
        Map<String, List<Integer>> missingByCacheKey = new LinkedHashMap<>();
        for (int index = 0; index < batch.projections().size(); index++) {
            VectorProjectionTarget target = batch.projections().get(index);
            String cacheKey = embeddingCacheKey(tenantKey, source, target);
            float[] cached = embeddingCache.find(source.context().revisionId(), cacheKey,
                    source.profile().dimension()).orElse(null);
            if (cached == null) {
                missingByCacheKey.computeIfAbsent(cacheKey, ignored -> new ArrayList<>()).add(index);
                continue;
            }
            result.set(index, embeddingValue(source, target, cached));
        }
        if (!missingByCacheKey.isEmpty()) {
            List<Map.Entry<String, List<Integer>>> misses = List.copyOf(missingByCacheKey.entrySet());
            List<float[]> embedded = embedding.embed(misses.stream()
                    .map(entry -> batch.projections().get(entry.getValue().get(0)).retrievalText()).toList(),
                    EmbeddingInputType.PASSAGE);
            if (embedded.size() != misses.size()) {
                throw new IllegalArgumentException("embedding response count does not match the batch");
            }
            for (int offset = 0; offset < misses.size(); offset++) {
                String cacheKey = misses.get(offset).getKey();
                float[] cached = embedded.get(offset);
                embeddingCache.put(source.context().revisionId(), cacheKey, cached);
                for (int index : misses.get(offset).getValue()) {
                    result.set(index, embeddingValue(source, batch.projections().get(index), cached));
                }
            }
        }
        return List.copyOf(result);
    }

    private VectorEmbeddingValue embeddingValue(VectorEmbeddingWork source, VectorProjectionTarget target,
                                                float[] values) {
        if (values == null || values.length != source.profile().dimension()) {
            throw new IllegalArgumentException("embedding cache identity is invalid");
        }
        return new VectorEmbeddingValue(target.chunkId(), target.vectorId(),
                target.projectionFingerprint(), values);
    }

    private String embeddingCacheKey(String tenantKey, VectorEmbeddingWork source,
                                     VectorProjectionTarget target) {
        return VectorGenerationProfile.sha256(tenantKey + ":" + source.context().revisionId() + ":"
                + target.retrievalTextSha256() + ":" + source.profile().tokenizerFingerprint() + ":"
                + source.profile().embeddingModelFingerprint() + ":" + EmbeddingInputType.PASSAGE.name());
    }

    private boolean heartbeat(ProcessingJobLease lease) {
        ProcessingJob job = lease.job();
        return queue.heartbeat(job.id(), job.leaseOwner(), lease.fenceToken(),
                clock.instant(), HEARTBEAT_EXTENSION);
    }

    private ProcessingJob nextJob(String revisionId, ProcessingJobStage stage,
                                  String workKey, String fingerprint) {
        return ProcessingJob.enqueue("job_" + UUID.randomUUID(), ProcessingJobTarget.forRevision(revisionId),
                stage, workKey, fingerprint, 0, clock.instant());
    }

    private WorkerFence fence(ProcessingJobLease lease) {
        return new WorkerFence(lease.job().id(), lease.job().leaseOwner(), lease.fenceToken());
    }

    private JobOutcome staleFence() {
        return JobOutcome.transientFailure(UploadErrorCode.STALE_FENCE.name());
    }

    private String batchPrefix(String revisionId, String generationId, int batchNo) {
        return "revisions/" + revisionId + "/projections/" + generationId + "/batches/"
                + String.format("%04d", batchNo);
    }
}
