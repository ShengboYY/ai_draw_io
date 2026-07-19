package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.WorkerFence;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionPlan;

import java.util.List;
import java.util.Optional;

/** Fenced transaction boundary for generation-scoped vector projection work. */
/** Fenced transactional boundary for vector planning, embedding, upsert, and manifest commits. */
public interface VectorProjectionWorkPort {
    Optional<RevisionProjectionContext> findCoordinatorWork(String revisionId, WorkerFence fence);
    boolean commitCoordinator(RevisionProjectionContext work, VectorProjectionPlan result,
                              List<ProcessingJob> nextJobs, WorkerFence fence);
    Optional<VectorEmbeddingWork> findEmbeddingWork(String revisionId, String workKey, WorkerFence fence);
    boolean commitEmbedding(VectorEmbeddingWork work, VectorBatchArtifactResult result,
                            ProcessingJob nextJob, WorkerFence fence);
    Optional<VectorUpsertWork> findUpsertWork(String revisionId, String workKey, WorkerFence fence);
    boolean commitUpsert(VectorUpsertWork work, VectorBatchPayload payload,
                         ProcessingJob verificationJob, WorkerFence fence);
    Optional<VectorManifestWork> findManifestWork(String revisionId, String workKey, WorkerFence fence);
    boolean commitManifest(VectorManifestWork work, VectorProjectionManifestResult result,
                           ProcessingJob nextJob, WorkerFence fence);
    Optional<RevisionPublicationWork> findPublicationWork(String revisionId, String workKey,
                                                          WorkerFence fence);
    boolean commitPublication(RevisionPublicationWork work, WorkerFence fence);
}
