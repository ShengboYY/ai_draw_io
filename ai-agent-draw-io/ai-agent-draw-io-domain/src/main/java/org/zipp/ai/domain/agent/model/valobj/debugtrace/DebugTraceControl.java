package org.zipp.ai.domain.agent.model.valobj.debugtrace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Admin-created switch that allows sensitive debug trace capture for a narrow scope. */
@Data
@Builder
public class DebugTraceControl {

    private String id;
    private String createdByUserId;
    private String scopeUserId;
    private String scopeRunId;
    private Instant scopeStartsAt;
    private Instant scopeEndsAt;
    private boolean enabled;
    private Instant createdAt;
    private Instant disabledAt;
}
