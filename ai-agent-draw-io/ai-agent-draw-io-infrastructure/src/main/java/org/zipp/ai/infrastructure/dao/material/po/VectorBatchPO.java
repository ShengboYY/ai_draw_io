package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class VectorBatchPO {
    private String revisionId;
    private String indexGenerationId;
    private int batchNo;
    private String workKey;
    private String inputFingerprint;
    private String vectorObjectKey;
    private String vectorObjectVersionId;
    private String vectorContentSha256;
    private Long vectorByteSize;
    private String vectorContentType;
    private String state;
}
