package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.citation.port.ManualProvenancePort;
import org.zipp.ai.infrastructure.dao.grounding.IManualProvenanceMapper;
import org.zipp.ai.infrastructure.dao.grounding.CellProvenanceTypeRowPO;
import org.zipp.ai.domain.citation.model.valobj.SupportType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
public class MySqlManualProvenanceAdapter implements ManualProvenancePort {
    private final IManualProvenanceMapper mapper;

    public MySqlManualProvenanceAdapter(IManualProvenanceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Map<String, StoredProvenance> findProvenance(String ownerKey, String diagramId, long canvasVersion) {
        LinkedHashMap<String, StoredProvenance> result = new LinkedHashMap<>();
        for (CellProvenanceTypeRowPO row : mapper.selectProvenance(ownerKey, diagramId, canvasVersion)) {
            result.put(row.getCellId(), new StoredProvenance(
                    row.getProvenanceRef(), SupportType.valueOf(row.getSupportType()),
                    row.getCurrentCitationId(), row.getSemanticHash()));
        }
        return Map.copyOf(result);
    }

    @Override
    public Optional<StoredProvenance> findOwnedProvenance(String ownerKey, String provenanceRef) {
        CellProvenanceTypeRowPO row = mapper.selectOwnedProvenance(ownerKey, provenanceRef);
        return Optional.ofNullable(row).map(value -> new StoredProvenance(value.getProvenanceRef(),
                SupportType.valueOf(value.getSupportType()), value.getCurrentCitationId(),
                value.getSemanticHash()));
    }

    @Override
    public void reconcile(ManualReconciliationPlan plan) {
        if (mapper.insertCanvasVersion(plan) != 1) {
            throw new IllegalStateException("MANUAL_CANVAS_VERSION_NOT_INSERTED");
        }
        mapper.copyInheritedProvenance(plan);
        plan.manualProvenance().forEach((cellId, provenance) -> {
            if (mapper.insertManualProvenance(plan, cellId, provenance) != 1) {
                throw new IllegalStateException("MANUAL_PROVENANCE_NOT_INSERTED");
            }
            if (provenance.currentCitationId() != null) {
                // Imported grounded cells pin every source used by the original cell-level citation set.
                mapper.upsertImportedPins(plan, provenance.currentCitationId());
            }
        });
    }
}
