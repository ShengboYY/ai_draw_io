package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RevisionStructurePagePO {
    private String revisionId;
    private String versionId;
    private long revisionFenceGeneration;
    private long materialLifecycleGeneration;
    private String processingFingerprint;
    private String pageId;
    private int pageNo;
    private String pageImageKey;
    private String pageImageVersionId;
    private String pageImageSha256;
    private long pageImageSize;
    private String pageImageContentType;
    private String canonicalKey;
    private String canonicalVersionId;
    private String canonicalSha256;
    private long canonicalSize;
    private String canonicalContentType;
}
