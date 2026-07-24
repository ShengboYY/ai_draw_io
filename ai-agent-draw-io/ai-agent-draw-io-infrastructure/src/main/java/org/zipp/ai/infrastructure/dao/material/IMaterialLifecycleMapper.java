package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialDeletionImpactPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialScopeLinkPO;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IMaterialLifecycleMapper {
    MaterialPO selectOwnedLifecycle(@Param("ownerType") String ownerType,
                                    @Param("ownerKey") String ownerKey,
                                    @Param("materialId") String materialId);
    MaterialPO lockOwnedLifecycle(@Param("ownerType") String ownerType,
                                  @Param("ownerKey") String ownerKey,
                                  @Param("materialId") String materialId);
    List<MaterialScopeLinkPO> selectLifecycleScopes(@Param("materialId") String materialId);
    MaterialPO selectLifecycleRequest(@Param("ownerKey") String ownerKey,
                                      @Param("materialId") String materialId,
                                      @Param("action") String action,
                                      @Param("requestFingerprint") String requestFingerprint);
    MaterialPO selectLifecycleRequestForUpdate(@Param("ownerKey") String ownerKey,
                                               @Param("materialId") String materialId,
                                               @Param("action") String action,
                                               @Param("requestFingerprint") String requestFingerprint);
    int insertLifecycleScope(@Param("linkId") String linkId,
                             @Param("materialId") String materialId,
                             @Param("ownerType") String ownerType,
                             @Param("ownerKey") String ownerKey,
                             @Param("scopeType") String scopeType,
                             @Param("scopeKey") String scopeKey);
    int updateLifecycle(@Param("material") MaterialPO material,
                        @Param("expectedGeneration") long expectedGeneration);
    int countPendingVectorCleanup(@Param("materialId") String materialId);
    int cancelQueuedMaterialJobs(@Param("materialId") String materialId);
    int insertDeletionTask(@Param("taskId") String taskId,
                           @Param("materialId") String materialId,
                           @Param("lifecycleGeneration") long lifecycleGeneration,
                           @Param("notBefore") Instant notBefore);
    int insertLifecycleRequest(@Param("ownerKey") String ownerKey,
                               @Param("action") String action,
                               @Param("requestFingerprint") String requestFingerprint,
                               @Param("material") MaterialPO material);
    int countOwnedLifecycleDiagram(@Param("ownerKey") String ownerKey,
                                   @Param("diagramId") String diagramId);
    int countOwnedLifecycleChartbook(@Param("ownerKey") String ownerKey,
                                     @Param("chartbookId") String chartbookId);
    int countOriginConversation(@Param("ownerKey") String ownerKey,
                                @Param("conversationId") String conversationId);
    MaterialDeletionImpactPO selectDeletionImpact(@Param("ownerType") String ownerType,
                                                  @Param("ownerKey") String ownerKey,
                                                  @Param("materialId") String materialId);
    List<String> selectDeletionImpactVersionIds(@Param("materialId") String materialId);
    List<String> selectDeletionImpactPinnedSourceIds(@Param("materialId") String materialId);
    List<String> selectDeletionImpactDiagramIds(@Param("materialId") String materialId);
    List<String> selectDeletionImpactChartbookIds(@Param("materialId") String materialId);
    List<String> selectDeletionImpactCitationIds(@Param("materialId") String materialId);
    List<MaterialPO> selectExpiredTemporary(@Param("now") Instant now,
                                            @Param("limit") int limit);
    List<MaterialPO> selectExpiredTrash(@Param("now") Instant now,
                                        @Param("limit") int limit);
    List<MaterialPO> selectOwnerDeletionCandidates(@Param("ownerKey") String ownerKey,
                                                   @Param("limit") int limit);
}
