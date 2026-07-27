package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RetrievalChunkEvidencePO {
    private String retrievalChunkId;
    private String evidenceId;
    private String role;
    private int ordinal;
    private Integer charStart;
    private Integer charEnd;
}
