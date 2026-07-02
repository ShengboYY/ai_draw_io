package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for encrypted user model credentials. */
@Data
public class ModelCredentialPO {

    private String id;
    private String userId;
    private String provider;
    private String baseUrl;
    private String model;
    private String completionPath;
    private String displayName;
    private String encryptedApiKey;
    private String encryptionProvider;
    private String encryptionKeyId;
    private String encryptionNonce;
    private String keyLastFour;
    private String status;
    private Date createdAt;
    private Date updatedAt;
    private Date disabledAt;
    private Date deletedAt;
}
