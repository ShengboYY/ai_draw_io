package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Read shape for user/admin listings. It carries masked key display only and never exposes raw or
 * encrypted API key bytes.
 */
@Data
@Builder
public class ModelCredentialSummary {

    private String id;
    private String provider;
    private String baseUrl;
    private String model;
    private String completionPath;
    private String displayName;
    private String maskedApiKey;
    private String encryptionProvider;
    private String encryptionKeyId;
    private String keyLastFour;
    private ModelCredentialStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant disabledAt;
}
