package org.zipp.ai.infrastructure.dao.grounding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ICitationQueryMapper {
    List<CellCitationRowPO> selectCellCitations(@Param("ownerKey") String ownerKey,
                                                @Param("diagramId") String diagramId,
                                                @Param("cellId") String cellId,
                                                @Param("canvasVersion") Long canvasVersion,
                                                @Param("provenanceRef") String provenanceRef);
}
