package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class RevisionPageWorkPO {
    private int pageNo;
    private double width;
    private double height;
    private String ocrStatus;
    private String pageImageKey;
    private String pageImageVersionId;
    private String pageImageSha256;
    private long pageImageSize;
    private String pageImageContentType;
    private String nativeKey;
    private String nativeVersionId;
    private String nativeSha256;
    private long nativeSize;
    private String nativeContentType;
    private String rawKey;
    private String rawVersionId;
    private String rawSha256;
    private Long rawSize;
    private String rawContentType;
}
