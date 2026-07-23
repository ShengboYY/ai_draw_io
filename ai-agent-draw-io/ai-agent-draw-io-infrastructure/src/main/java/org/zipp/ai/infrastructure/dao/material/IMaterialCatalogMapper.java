package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.MaterialCatalogItemPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialCatalogVersionPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialScopeLinkPO;

import java.util.List;

@Mapper
public interface IMaterialCatalogMapper {
    List<MaterialCatalogItemPO> selectLibraryMaterials(@Param("ownerType") String ownerType,
                                                       @Param("ownerKey") String ownerKey,
                                                       @Param("lifecycleState") String lifecycleState,
                                                       @Param("query") String query,
                                                       @Param("limit") int limit,
                                                       @Param("offset") int offset);
    long countLibraryMaterials(@Param("ownerType") String ownerType,
                               @Param("ownerKey") String ownerKey,
                               @Param("lifecycleState") String lifecycleState,
                               @Param("query") String query);
    List<MaterialCatalogItemPO> selectScopedMaterials(@Param("ownerType") String ownerType,
                                                      @Param("ownerKey") String ownerKey,
                                                      @Param("scopeType") String scopeType,
                                                      @Param("scopeKey") String scopeKey,
                                                      @Param("lifecycleState") String lifecycleState,
                                                      @Param("limit") int limit,
                                                      @Param("offset") int offset);
    long countScopedMaterials(@Param("ownerType") String ownerType,
                              @Param("ownerKey") String ownerKey,
                              @Param("scopeType") String scopeType,
                              @Param("scopeKey") String scopeKey,
                              @Param("lifecycleState") String lifecycleState);
    MaterialCatalogItemPO selectOwnedMaterial(@Param("ownerType") String ownerType,
                                              @Param("ownerKey") String ownerKey,
                                              @Param("materialId") String materialId);
    List<MaterialCatalogVersionPO> selectOwnedVersions(@Param("ownerKey") String ownerKey,
                                                       @Param("materialId") String materialId);
    List<MaterialScopeLinkPO> selectOwnedScopes(@Param("ownerType") String ownerType,
                                                @Param("ownerKey") String ownerKey,
                                                @Param("materialId") String materialId);
    int countOwnedDiagram(@Param("ownerKey") String ownerKey, @Param("diagramId") String diagramId);
    int countOwnedActiveChartbook(@Param("ownerKey") String ownerKey,
                                  @Param("chartbookId") String chartbookId);
    String lockOwnedActiveMaterial(@Param("ownerType") String ownerType,
                                   @Param("ownerKey") String ownerKey,
                                   @Param("materialId") String materialId);
    int countOwnedScopes(@Param("materialId") String materialId);
    int countScope(@Param("materialId") String materialId,
                   @Param("scopeType") String scopeType,
                   @Param("scopeKey") String scopeKey);
    int insertOwnedScope(@Param("linkId") String linkId,
                         @Param("ownerType") String ownerType,
                         @Param("ownerKey") String ownerKey,
                         @Param("materialId") String materialId,
                         @Param("scopeType") String scopeType,
                         @Param("scopeKey") String scopeKey);
    int deleteOwnedScope(@Param("materialId") String materialId,
                         @Param("linkId") String linkId);
}
