package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookCatalogPO;

import java.time.Instant;
import java.util.List;

@Mapper
public interface IChartbookCatalogMapper {
    int insertChartbook(@Param("id") String id, @Param("ownerKey") String ownerKey,
                        @Param("name") String name, @Param("idempotencyKey") String idempotencyKey,
                        @Param("createdAt") Instant createdAt);
    ChartbookCatalogPO selectByIdempotencyKey(@Param("ownerKey") String ownerKey,
                                              @Param("idempotencyKey") String idempotencyKey);
    List<ChartbookCatalogPO> selectAll(@Param("ownerKey") String ownerKey);
    ChartbookCatalogPO selectOne(@Param("ownerKey") String ownerKey,
                                 @Param("chartbookId") String chartbookId);
    List<String> selectDiagramIds(@Param("ownerKey") String ownerKey,
                                  @Param("chartbookId") String chartbookId);
    List<String> selectMaterialIds(@Param("ownerKey") String ownerKey,
                                   @Param("chartbookId") String chartbookId);
    int rename(@Param("ownerKey") String ownerKey, @Param("chartbookId") String chartbookId,
               @Param("name") String name);
    int archive(@Param("ownerKey") String ownerKey, @Param("chartbookId") String chartbookId);
    int detachDiagrams(@Param("ownerKey") String ownerKey, @Param("chartbookId") String chartbookId);
    int assignDiagram(@Param("ownerKey") String ownerKey, @Param("diagramId") String diagramId,
                      @Param("chartbookId") String chartbookId);
    int removeDiagram(@Param("ownerKey") String ownerKey, @Param("diagramId") String diagramId);
}
