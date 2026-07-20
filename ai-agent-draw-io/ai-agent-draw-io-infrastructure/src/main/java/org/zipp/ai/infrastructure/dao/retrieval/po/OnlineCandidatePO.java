package org.zipp.ai.infrastructure.dao.retrieval.po;

import lombok.Data;

@Data
public class OnlineCandidatePO {
    private String chunkId;
    private String vectorId;
    private String modality;
    private double score;
    private String evidenceId;
    private String materialId;
    private String versionId;
    private String revisionId;
    private int pageNumber;
    private double qualityScore;
    private String retrievalTextObjectKey;
    private String retrievalTextObjectVersionId;
    private String retrievalTextSha256;
    private long retrievalTextByteSize;
    private String retrievalTextContentType;
    private String sourceLabel;
}
