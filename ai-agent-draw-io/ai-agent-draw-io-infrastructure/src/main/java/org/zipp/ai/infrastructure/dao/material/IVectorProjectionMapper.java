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
    VectorProjectionWorkPO selectCompatibilityCoordinatorWork(@Param("revisionId") String revisionId,
                                                              @Param("workKey") String workKey,
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
    int updateGenerationTargetState(@Param("revisionId") String revisionId,
                                    @Param("generationId") String generationId,
                                    @Param("expectedState") String expectedState,
                                    @Param("state") String state);
    int countGenerationRoutedFence(@Param("revisionId") String revisionId,
                                   @Param("generationId") String generationId,
                                   @Param("stage") String stage,
                                   @Param("jobId") String jobId,
                                   @Param("workerId") String workerId,
                                   @Param("fenceToken") long fenceToken);
    int insertCompatibilityProfile(@Param("generationId") String generationId,
                                   @Param("tokenizerFingerprint") String tokenizerFingerprint,
                                   @Param("campaignFingerprint") String campaignFingerprint,
                                   @Param("registeredAt") Instant registeredAt);
    String selectCompatibilityTokenizer(@Param("generationId") String generationId);
    int insertRequiredGenerationTargets(@Param("generationId") String generationId,
                                        @Param("tokenizerFingerprint") String tokenizerFingerprint,
                                        @Param("requiredAt") Instant requiredAt);
    int advanceCompatibilityTargetGeneration(@Param("generationId") String generationId,
                                             @Param("addedTargetCount") int addedTargetCount);
    List<VectorProjectionWorkPO> selectPendingGenerationTargets(@Param("generationId") String generationId,
                                                                @Param("limit") int limit);
    List<VectorProjectionWorkPO> selectPendingGenerationPublications(
            @Param("generationId") String generationId, @Param("limit") int limit);
    GenerationBackfillStatusPO selectGenerationBackfillStatus(@Param("generationId") String generationId);
    int beginGenerationShadow(@Param("generationId") String generationId,
                              @Param("shadowStartedAt") Instant shadowStartedAt);
    int insertShadowReport(GenerationShadowReportPO report);
    GenerationShadowReportPO selectShadowReport(@Param("generationId") String generationId,
                                                @Param("reportId") String reportId);
    List<String> lockGenerationTargets(@Param("generationId") String generationId);
    int retireActiveGeneration(@Param("generationId") String generationId,
                               @Param("retiredAt") Instant retiredAt,
                               @Param("rollbackUntil") Instant rollbackUntil);
    int activateShadowGeneration(@Param("generationId") String generationId,
                                 @Param("previousGenerationId") String previousGenerationId,
                                 @Param("reportId") String reportId,
                                 @Param("activatedAt") Instant activatedAt);
    RagIndexGenerationPO selectGenerationForUpdate(@Param("generationId") String generationId);
    int retireRolledBackGeneration(@Param("generationId") String generationId,
                                   @Param("retiredAt") Instant retiredAt);
    int restoreRetiredGeneration(@Param("generationId") String generationId,
                                 @Param("activatedAt") Instant activatedAt);
}
