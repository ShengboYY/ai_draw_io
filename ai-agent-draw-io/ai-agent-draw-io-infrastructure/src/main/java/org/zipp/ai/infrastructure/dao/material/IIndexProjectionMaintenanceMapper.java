package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingJobPO;
import org.zipp.ai.infrastructure.dao.material.po.RagIndexGenerationPO;
import org.zipp.ai.infrastructure.dao.material.po.VectorProjectionWorkPO;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IIndexProjectionMaintenanceMapper {
    List<VectorProjectionWorkPO> selectBatchesDue(@Param("generationId") String generationId,
                                                   @Param("dueBefore") Instant dueBefore,
                                                   @Param("limit") int limit);
    int markBatchChecked(@Param("generationId") String generationId,
                         @Param("revisionId") String revisionId,
                         @Param("batchNo") int batchNo,
                         @Param("batchInputFingerprint") String batchInputFingerprint,
                         @Param("checkedAt") Instant checkedAt);
    int closeTerminalRepair(@Param("generationId") String generationId,
                            @Param("revisionId") String revisionId,
                            @Param("batchNo") int batchNo,
                            @Param("completedAt") Instant completedAt);
    int insertRepairAudit(@Param("repairId") String repairId,
                          @Param("generationId") String generationId,
                          @Param("revisionId") String revisionId,
                          @Param("batchNo") int batchNo,
                          @Param("workKey") String workKey,
                          @Param("inputFingerprint") String inputFingerprint,
                          @Param("batchInputFingerprint") String batchInputFingerprint,
                          @Param("missingIdsFingerprint") String missingIdsFingerprint,
                          @Param("missingCount") int missingCount,
                          @Param("requestedAt") Instant requestedAt);
    List<VectorProjectionWorkPO> selectRepairWork(@Param("revisionId") String revisionId,
                                                   @Param("workKey") String workKey,
                                                   @Param("jobId") String jobId,
                                                   @Param("workerId") String workerId,
                                                   @Param("fenceToken") long fenceToken);
    int markRepairRunning(@Param("repairId") String repairId);
    int countRepairFence(@Param("repairId") String repairId,
                         @Param("jobId") String jobId,
                         @Param("workerId") String workerId,
                         @Param("fenceToken") long fenceToken);
    int completeRepair(@Param("repairId") String repairId,
                       @Param("completedAt") Instant completedAt);
    int insertProviderCursor(@Param("generationId") String generationId,
                             @Param("updatedAt") Instant updatedAt);
    String selectProviderCursor(@Param("generationId") String generationId);
    int advanceProviderCursor(@Param("generationId") String generationId,
                              @Param("expectedCursor") String expectedCursor,
                              @Param("nextCursor") String nextCursor,
                              @Param("updatedAt") Instant updatedAt);
    List<String> selectKnownVectorIds(@Param("vectorIds") List<String> vectorIds);
    int upsertOrphanDeletionIntent(@Param("deletionId") String deletionId,
                                   @Param("generationId") String generationId,
                                   @Param("vectorIdsFingerprint") String vectorIdsFingerprint,
                                   @Param("vectorCount") int vectorCount,
                                   @Param("requestedAt") Instant requestedAt);
    int completeOrphanDeletion(@Param("deletionId") String deletionId,
                               @Param("completedAt") Instant completedAt);
    int insertTemporaryCleanupCursor(@Param("generationId") String generationId,
                                     @Param("updatedAt") Instant updatedAt);
    String selectTemporaryCleanupCursor(@Param("generationId") String generationId);
    int advanceTemporaryCleanupCursor(@Param("generationId") String generationId,
                                      @Param("expectedMaterialId") String expectedMaterialId,
                                      @Param("nextMaterialId") String nextMaterialId,
                                      @Param("updatedAt") Instant updatedAt);
    String selectTemporaryCleanupMaterial(@Param("generationId") String generationId,
                                          @Param("afterMaterialId") String afterMaterialId,
                                          @Param("now") Instant now);
    List<String> selectTemporaryCleanupVectorIds(@Param("generationId") String generationId,
                                                  @Param("materialId") String materialId,
                                                  @Param("now") Instant now,
                                                  @Param("limit") int limit);
    String lockExpiredTemporaryCleanupMaterial(@Param("materialId") String materialId,
                                                @Param("now") Instant now);
    int claimTemporaryConversationVectorsForDeletion(@Param("generationId") String generationId,
                                                      @Param("materialId") String materialId,
                                                      @Param("vectorIds") List<String> vectorIds,
                                                      @Param("deletedAt") Instant deletedAt);
    RagIndexGenerationPO selectPendingProviderDeletionGeneration();
    List<String> selectTemporaryProviderDeletionRetries(@Param("generationId") String generationId,
                                                        @Param("limit") int limit);
    int completeTemporaryProviderDeletion(@Param("generationId") String generationId,
                                          @Param("vectorIds") List<String> vectorIds,
                                          @Param("completedAt") Instant completedAt);
    RagIndexGenerationPO selectRetiredCleanupGeneration(
            @Param("now") Instant now,
            @Param("minimumGraceSeconds") long minimumGraceSeconds);
    int claimRetiredGenerationCleanup(@Param("generationId") String generationId,
                                      @Param("now") Instant now,
                                      @Param("minimumGraceSeconds") long minimumGraceSeconds);
    Instant selectRetiredEligibleAt(@Param("generationId") String generationId,
                                    @Param("now") Instant now,
                                    @Param("minimumGraceSeconds") long minimumGraceSeconds);
    List<String> selectRetiredVectorIds(@Param("generationId") String generationId,
                                        @Param("limit") int limit);
    int markRetiredVectorsDeleted(@Param("generationId") String generationId,
                                  @Param("vectorIds") List<String> vectorIds,
                                  @Param("deletedAt") Instant deletedAt);
    int completeRetiredGeneration(@Param("generationId") String generationId,
                                  @Param("completedAt") Instant completedAt);
    String lockTargetState(@Param("generationId") String generationId,
                           @Param("revisionId") String revisionId);
    ProcessingJobPO selectFailedTargetJobForUpdate(@Param("generationId") String generationId,
                                                    @Param("revisionId") String revisionId);
    int insertTargetRepairAudit(@Param("repairId") String repairId,
                                @Param("generationId") String generationId,
                                @Param("revisionId") String revisionId,
                                @Param("failedJobId") String failedJobId,
                                @Param("failedErrorCode") String failedErrorCode,
                                @Param("requestedByHash") String requestedByHash,
                                @Param("reasonCode") String reasonCode,
                                @Param("requestedAt") Instant requestedAt);
    int requeueFailedJob(@Param("jobId") String jobId, @Param("requestedAt") Instant requestedAt);
}
