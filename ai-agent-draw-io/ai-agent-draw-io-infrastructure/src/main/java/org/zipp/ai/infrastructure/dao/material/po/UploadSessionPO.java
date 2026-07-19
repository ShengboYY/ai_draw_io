package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class UploadSessionPO {
    private String id;
    private String ownerType;
    private String ownerKey;
    private String idempotencyKey;
    private String displayName;
    private String targetScopeType;
    private String targetScopeKey;
    private String targetRetentionClass;
    private String newVersionOfMaterialId;
    private long expectedSize;
    private String expectedSha256;
    private String declaredMime;
    private String quarantineBucket;
    private String quarantineKey;
    private String quarantineObjectVersionId;
    private String s3Etag;
    private String s3ChecksumSha256;
    private Instant policyExpiresAt;
    private String state;
    private String materialId;
    private String versionId;
    private long generation;
    private String errorCode;
    private Long actualSize;
    private String actualSha256;
    private String detectedMime;
    private Integer pageCount;
    private Long pixelCount;
    private String securityStatus;
    private Instant createdAt;
}
