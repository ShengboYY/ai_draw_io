package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RetrievalChunkPO {
    private String id;
    private String versionId;
    private String revisionId;
    private String pageId;
    private String sectionId;
    private String chunkType;
    private String modality;
    private String languagePrimary;
    private boolean citable;
    private String indexMode;
    private String duplicateClusterId;
    private String canonicalChunkId;
    private String retrievalTextObjectKey;
    private String retrievalTextObjectVersionId;
    private String retrievalTextSha256;
    private long retrievalTextByteSize;
    private String retrievalTextContentType;
    private String parentContextObjectKey;
    private String parentContextObjectVersionId;
    private int tokenCount;
    private double qualityScore;
    private int structuralOrdinal;
    private String status;
}
