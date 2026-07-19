package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RetrievalExactTermPO {
    private String retrievalChunkId;
    private String ownerType;
    private String ownerKey;
    private String versionId;
    private String revisionId;
    private String normalizedTerm;
    private String termType;
}
