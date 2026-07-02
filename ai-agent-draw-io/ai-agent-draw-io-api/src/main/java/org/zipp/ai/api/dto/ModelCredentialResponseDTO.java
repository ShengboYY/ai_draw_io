package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

/** Response shape for saved model credentials; raw and encrypted API keys are deliberately absent. */
@Data
public class ModelCredentialResponseDTO {

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
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant disabledAt;
}
