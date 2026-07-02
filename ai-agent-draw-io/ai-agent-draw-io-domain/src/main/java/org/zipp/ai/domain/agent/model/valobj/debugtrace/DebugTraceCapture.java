package org.zipp.ai.domain.agent.model.valobj.debugtrace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Captured debug content plus metadata that remains after content retention expiry. */
@Data
@Builder
public class DebugTraceCapture {

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
