package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminDebugTraceRetentionRequestDTO {

    private Instant expiresAt;
}
