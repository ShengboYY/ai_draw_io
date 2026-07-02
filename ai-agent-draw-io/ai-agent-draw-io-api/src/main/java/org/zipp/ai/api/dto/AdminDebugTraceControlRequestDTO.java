package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminDebugTraceControlRequestDTO {

    private String userId;
    private String runId;
    private Instant startsAt;
    private Instant endsAt;
}
