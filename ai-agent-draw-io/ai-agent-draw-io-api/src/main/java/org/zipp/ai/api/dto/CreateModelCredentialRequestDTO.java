package org.zipp.ai.api.dto;

import lombok.Data;

@Data
public class CreateModelCredentialRequestDTO {

    private String provider;
    private String baseUrl;
    private String model;
    private String completionPath;
    private String displayName;
    private String apiKey;
}
