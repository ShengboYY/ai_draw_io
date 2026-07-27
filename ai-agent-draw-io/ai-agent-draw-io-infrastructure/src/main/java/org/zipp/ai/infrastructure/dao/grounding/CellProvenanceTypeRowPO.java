package org.zipp.ai.infrastructure.dao.grounding;

import lombok.Data;

/** Minimal projection used to rebuild server-owned metadata during a manual save. */
@Data
public class CellProvenanceTypeRowPO {
    private String cellId;
    private String supportType;
    private String provenanceRef;
    private String currentCitationId;
    private String semanticHash;
}
