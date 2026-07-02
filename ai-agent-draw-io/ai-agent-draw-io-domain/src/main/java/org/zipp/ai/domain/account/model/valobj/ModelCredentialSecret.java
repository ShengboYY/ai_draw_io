package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * Ephemeral chat-time credential. It must stay inside the backend model-call path and never be
 * returned by an API response.
 */
@Data
@Builder
public class ModelCredentialSecret {

    private String id;
    private String baseUrl;
    private String apiKey;
    private String completionPath;
    private String model;
}
