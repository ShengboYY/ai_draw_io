package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminDebugTraceControlDTO {

    private String id;
    private String scopeUserId;
    private String scopeRunId;
    private Instant scopeStartsAt;
    private Instant scopeEndsAt;
    private boolean enabled;
    private Instant createdAt;
}
