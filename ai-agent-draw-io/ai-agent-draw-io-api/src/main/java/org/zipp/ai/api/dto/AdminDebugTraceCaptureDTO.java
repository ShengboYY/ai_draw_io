package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminDebugTraceCaptureDTO {

    private String id;
    private String controlId;
    private String userId;
    private String runId;
    private String eventType;
    private String content;
    private String contentSha256;
    private Instant contentExpiresAt;
    private Instant contentDeletedAt;
    private Instant createdAt;
}
