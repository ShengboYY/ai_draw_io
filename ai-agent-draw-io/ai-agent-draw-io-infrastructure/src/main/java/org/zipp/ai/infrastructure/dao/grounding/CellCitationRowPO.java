package org.zipp.ai.infrastructure.dao.grounding;

import lombok.Data;

/** Flat MyBatis row grouped into the citation read model by the adapter. */
@Data
public class CellCitationRowPO {
    private String citationId;
    private String cellId;
    private String provenanceRef;
    private String statementKey;
    private String supportType;
    private String citationState;
    private String citationKey;
    private String materialId;
    private String displayName;
    private String versionId;
    private Integer versionNo;
    private Integer pageNumber;
    private String modality;
    private String sourceState;
    private String processingRevisionId;
    private String bboxJson;
    private String origin;
    private Boolean excerptAvailable;
    private Boolean previewAvailable;
    private String deletedAt;
    private String displayTextObjectKey;
    private String displayTextObjectVersionId;
}
