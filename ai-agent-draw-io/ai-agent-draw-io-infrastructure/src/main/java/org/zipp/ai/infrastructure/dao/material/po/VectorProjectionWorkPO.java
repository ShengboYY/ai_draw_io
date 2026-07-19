package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class VectorProjectionWorkPO {
    private String revisionId;
    private String versionId;
    private String materialId;
    private String ownerType;
    private String ownerKey;
    private long revisionFenceGeneration;
    private long materialLifecycleGeneration;
    private String processingFingerprint;
    private String retrievalManifestKey;
    private String retrievalManifestVersionId;
    private String retrievalManifestSha256;
    private long retrievalManifestSize;
    private String retrievalManifestContentType;
    private String indexGenerationId;
    private String indexName;
    private String namespace;
    private String embeddingModel;
    private String embeddingModelFingerprint;
    private int dimension;
    private String metric;
    private String vectorSchemaVersion;
    private String tokenizerFingerprint;
    private int batchNo;
    private String workKey;
    private String batchInputFingerprint;
    private String vectorObjectKey;
    private String vectorObjectVersionId;
    private String vectorContentSha256;
    private long vectorByteSize;
    private String vectorContentType;
    private String projectionRole;
    private String chunkId;
    private String vectorId;
    private String projectionFingerprint;
    private int eligibleProjectionCount;
    private String chunkType;
    private String modality;
    private Integer pageNo;
    private String language;
}
