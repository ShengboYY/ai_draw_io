package org.zipp.ai.infrastructure.dao.grounding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.domain.citation.port.ManualProvenancePort;

@Mapper
public interface IManualProvenanceMapper {
    java.util.List<CellProvenanceTypeRowPO> selectProvenance(
            @Param("ownerKey") String ownerKey, @Param("diagramId") String diagramId,
            @Param("canvasVersion") long canvasVersion);
    CellProvenanceTypeRowPO selectOwnedProvenance(@Param("ownerKey") String ownerKey,
                                                  @Param("provenanceRef") String provenanceRef);
    int insertCanvasVersion(@Param("plan") ManualProvenancePort.ManualReconciliationPlan plan);
    int copyInheritedProvenance(@Param("plan") ManualProvenancePort.ManualReconciliationPlan plan);
    int insertManualProvenance(@Param("plan") ManualProvenancePort.ManualReconciliationPlan plan,
                               @Param("cellId") String cellId,
                               @Param("provenance") ManualProvenancePort.ManualCellProvenance provenance);
    int upsertImportedPins(@Param("plan") ManualProvenancePort.ManualReconciliationPlan plan,
                           @Param("citationId") String citationId);
}
