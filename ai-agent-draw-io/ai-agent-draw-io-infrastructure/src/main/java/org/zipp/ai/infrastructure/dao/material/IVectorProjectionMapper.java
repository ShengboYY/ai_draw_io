package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IVectorProjectionMapper {
    VectorProjectionWorkPO selectCoordinatorWork(@Param("revisionId") String revisionId,
                                                 @Param("jobId") String jobId,
                                                 @Param("workerId") String workerId,
                                                 @Param("fenceToken") long fenceToken);
    VectorProjectionWorkPO selectEmbeddingWork(@Param("revisionId") String revisionId,
                                               @Param("workKey") String workKey,
                                               @Param("jobId") String jobId,
                                               @Param("workerId") String workerId,
                                               @Param("fenceToken") long fenceToken);
    VectorProjectionWorkPO selectUpsertWork(@Param("revisionId") String revisionId,
                                            @Param("workKey") String workKey,
                                            @Param("jobId") String jobId,
                                            @Param("workerId") String workerId,
                                            @Param("fenceToken") long fenceToken);
    List<VectorProjectionWorkPO> selectBatchProjections(@Param("revisionId") String revisionId,
                                                        @Param("generationId") String generationId,
                                                        @Param("batchNo") int batchNo);
    List<VectorProjectionWorkPO> selectManifestWork(@Param("revisionId") String revisionId,
                                                    @Param("workKey") String workKey,
                                                    @Param("jobId") String jobId,
                                                    @Param("workerId") String workerId,
                                                    @Param("fenceToken") long fenceToken);
    List<VectorProjectionWorkPO> selectPublicationWork(@Param("revisionId") String revisionId,
                                                       @Param("workKey") String workKey,
                                                       @Param("jobId") String jobId,
                                                       @Param("workerId") String workerId,
                                                       @Param("fenceToken") long fenceToken);
    int insertGeneration(RagIndexGenerationPO generation);
    RagIndexGenerationPO selectGeneration(@Param("generationId") String generationId);
    int insertRevisionProjection(RevisionVectorProjectionPO projection);
    RevisionVectorProjectionPO selectRevisionProjection(@Param("revisionId") String revisionId,
                                                         @Param("generationId") String generationId);
    int insertBatch(VectorBatchPO batch);
    VectorBatchPO selectBatch(@Param("revisionId") String revisionId,
                              @Param("generationId") String generationId,
                              @Param("batchNo") int batchNo);
    int insertProjection(VectorProjectionPO projection);
    VectorProjectionPO selectProjection(@Param("chunkId") String chunkId,
                                        @Param("generationId") String generationId);
    int pinVectorBatch(@Param("revisionId") String revisionId,
                       @Param("generationId") String generationId,
                       @Param("batchNo") int batchNo,
                       @Param("objectKey") String objectKey,
                       @Param("objectVersionId") String objectVersionId,
                       @Param("contentSha256") String contentSha256,
                       @Param("byteSize") long byteSize,
                       @Param("contentType") String contentType);
    int updateProjectionBatchState(@Param("revisionId") String revisionId,
                                   @Param("generationId") String generationId,
                                   @Param("batchNo") int batchNo,
                                   @Param("expectedState") String expectedState,
                                   @Param("state") String state,
                                   @Param("indexedAt") Instant indexedAt);
    int updateBatchState(@Param("revisionId") String revisionId,
                         @Param("generationId") String generationId,
                         @Param("batchNo") int batchNo,
                         @Param("expectedState") String expectedState,
                         @Param("state") String state);
    int countIncompleteBatches(@Param("revisionId") String revisionId,
                               @Param("generationId") String generationId);
    String lockRevisionGate(@Param("revisionId") String revisionId);
    int updateRevisionProjectionState(@Param("revisionId") String revisionId,
                                      @Param("generationId") String generationId,
                                      @Param("expectedState") String expectedState,
                                      @Param("state") String state);
    int insertManifest(VectorProjectionManifestPO manifest);
    VectorProjectionManifestPO selectManifest(@Param("revisionId") String revisionId,
                                              @Param("generationId") String generationId);
    String lockGenerationState(@Param("generationId") String generationId);
    String lockMaterialLifecycleState(@Param("materialId") String materialId);
    String selectActiveGenerationForUpdate();
    int activateInitialGeneration(@Param("generationId") String generationId,
                                  @Param("activatedAt") Instant activatedAt);
    int publishRevision(@Param("revisionId") String revisionId,
                        @Param("revisionFenceGeneration") long revisionFenceGeneration,
                        @Param("state") String state,
                        @Param("publishedAt") Instant publishedAt);
    int activateVersionRevision(@Param("versionId") String versionId,
                                @Param("revisionId") String revisionId);
    int recordInitialProcessingUsage(@Param("revisionId") String revisionId);
}
