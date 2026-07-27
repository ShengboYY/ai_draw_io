package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IMaterialDeletionMapper {
    DeletionTaskPO selectClaimableForUpdate(@Param("now") Instant now);
    String lockRunningTask(@Param("taskId") String taskId,
                           @Param("expectedStage") String expectedStage,
                           @Param("expectedFenceToken") long expectedFenceToken);
    int claimTask(@Param("task") DeletionTaskPO task,
                  @Param("expectedFenceToken") long expectedFenceToken);
    int countActiveReadLeases(@Param("materialId") String materialId,
                              @Param("now") Instant now);
    MaterialPO lockDeletionMaterial(@Param("materialId") String materialId,
                                    @Param("lifecycleGeneration") long lifecycleGeneration);
    int beginDeleting(@Param("materialId") String materialId,
                      @Param("lifecycleGeneration") long lifecycleGeneration);
    int insertDeletedSourceTombstones(@Param("materialId") String materialId,
                                      @Param("deletedAt") Instant deletedAt);
    int insertCitationSourceTombstones(@Param("materialId") String materialId,
                                       @Param("deletedAt") Instant deletedAt);
    int deleteMaterialCitationEvidence(@Param("materialId") String materialId);
    int updateTombstonedCitationStates(@Param("materialId") String materialId);
    int markExclusiveBlobsDeletePending(@Param("materialId") String materialId);
    int upsertDeletionPreparationProof(@Param("materialId") String materialId,
                                       @Param("lifecycleGeneration") long lifecycleGeneration,
                                       @Param("tombstoneCount") long tombstoneCount);
    List<MaterialVectorLocationPO> selectVectorLocations(@Param("materialId") String materialId);
    List<MaterialObjectVersionPO> selectObjectVersions(@Param("materialId") String materialId);
    int markVectorScopeDeleted(@Param("materialId") String materialId,
                               @Param("indexName") String indexName,
                               @Param("deletedAt") Instant deletedAt);
    int updateVectorDeletionProof(@Param("materialId") String materialId,
                                  @Param("deletedCount") int deletedCount,
                                  @Param("requestIdsHash") String requestIdsHash,
                                  @Param("deletedAt") Instant deletedAt);
    int updateObjectDeletionProof(@Param("materialId") String materialId,
                                  @Param("deletedCount") int deletedCount,
                                  @Param("requestIdsHash") String requestIdsHash,
                                  @Param("deletedAt") Instant deletedAt);
    int completeDeletionProof(@Param("materialId") String materialId,
                              @Param("deletedAt") Instant deletedAt);
    int sanitizeDeletedMaterial(@Param("materialId") String materialId,
                                @Param("lifecycleGeneration") long lifecycleGeneration,
                                @Param("deletedAt") Instant deletedAt);
    int saveTaskTransition(@Param("task") DeletionTaskPO task,
                           @Param("expectedStage") String expectedStage,
                           @Param("expectedFenceToken") long expectedFenceToken);
    int requeueExpiredLeases(@Param("now") Instant now, @Param("limit") int limit);
}
