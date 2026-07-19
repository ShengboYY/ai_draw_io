package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RetrievalSearchDocumentPO {
    private String retrievalChunkId;
    private String ownerType;
    private String ownerKey;
    private String materialId;
    private String versionId;
    private String revisionId;
    private String wordSearchText;
    private String cjkSearchText;
    private String status;
}
