package org.zipp.ai.domain.account.model.entity;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialStatus;

import java.time.Instant;

/**
 * User-owned model credential. The raw API key is intentionally absent; only encrypted key material
 * and display-safe metadata live on the aggregate.
 */
@Data
@Builder
public class ModelCredential {

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
    private ModelCredentialStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant disabledAt;
    private Instant deletedAt;
}
