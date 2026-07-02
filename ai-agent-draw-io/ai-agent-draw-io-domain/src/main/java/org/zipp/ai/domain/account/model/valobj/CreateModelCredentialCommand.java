package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/** Request to save a verified user's model credential. */
@Data
@Builder
public class CreateModelCredentialCommand {

    private String userId;
    private String provider;
    private String baseUrl;
    private String model;
    private String completionPath;
    private String displayName;
    private String apiKey;
}
