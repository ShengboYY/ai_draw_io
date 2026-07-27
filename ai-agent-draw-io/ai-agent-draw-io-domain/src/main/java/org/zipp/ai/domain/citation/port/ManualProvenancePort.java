package org.zipp.ai.domain.citation.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.zipp.ai.domain.citation.model.valobj.SupportType;

/** Persists one manual canvas version and its reconciled cell provenance. */
public interface ManualProvenancePort {
    Map<String, StoredProvenance> findProvenance(String ownerKey, String diagramId, long canvasVersion);
    default Optional<StoredProvenance> findOwnedProvenance(String ownerKey, String provenanceRef) {
        return Optional.empty();
    }
    void reconcile(ManualReconciliationPlan plan);

    record StoredProvenance(String provenanceRef, SupportType supportType,
                            String currentCitationId, String semanticHash) {
        public StoredProvenance(String provenanceRef, SupportType supportType) {
            this(provenanceRef, supportType, null, null);
        }
        public StoredProvenance(String provenanceRef, SupportType supportType, String currentCitationId) {
            this(provenanceRef, supportType, currentCitationId, null);
        }
    }

    record ManualCellProvenance(String provenanceRef, String semanticHash,
                                SupportType supportType, String currentCitationId) { }

    record ManualReconciliationPlan(String ownerKey, String diagramId, long previousVersion,
                                    long canvasVersion, String contentHash, String canvasXml,
                                    Map<String, ManualCellProvenance> manualProvenance,
                                    List<String> removedCellIds) {
        public ManualReconciliationPlan {
            manualProvenance = Map.copyOf(manualProvenance == null ? Map.of() : manualProvenance);
            removedCellIds = List.copyOf(removedCellIds == null ? List.of() : removedCellIds);
        }
    }
}
