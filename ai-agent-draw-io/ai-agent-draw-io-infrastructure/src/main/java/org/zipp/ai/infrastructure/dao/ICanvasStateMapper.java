package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.CanvasStatePO;

@Mapper
public interface ICanvasStateMapper {

    CanvasStatePO selectByUserAndDiagram(@Param("userId") String userId, @Param("diagramId") String diagramId);

    int upsertDiagram(CanvasStatePO state);

    int upsertCanvasState(CanvasStatePO state);

    int updateCanvasStateByVersion(CanvasStatePO state);

    int syncDiagramVersion(@Param("userId") String userId,
                           @Param("diagramId") String diagramId,
                           @Param("version") Long version);

}
