package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RevisionRetrievalWorkPO {
    private String revisionId;
    private String versionId;
    private String materialId;
    private String ownerType;
    private String ownerKey;
    private long revisionFenceGeneration;
    private long materialLifecycleGeneration;
    private String processingFingerprint;
    private String evidenceManifestKey;
    private String evidenceManifestVersionId;
    private String evidenceManifestSha256;
    private long evidenceManifestSize;
    private String evidenceManifestContentType;
}
