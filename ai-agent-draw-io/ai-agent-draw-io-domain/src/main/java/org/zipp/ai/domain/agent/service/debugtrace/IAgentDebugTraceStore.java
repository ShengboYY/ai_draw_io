package org.zipp.ai.domain.agent.service.debugtrace;

import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;

import java.time.Instant;
import java.util.List;

public interface IAgentDebugTraceStore {

    void insertControl(DebugTraceControl control);

    /** Return enabled controls only; the service applies the user/run/time scope rules. */
    List<DebugTraceControl> listEnabledControls();

    void insertCapture(DebugTraceCapture capture);

    default List<DebugTraceCapture> listCapturesByRunId(String runId) {
        return List.of();
    }

    int deleteExpiredContent(Instant now);

    int extendRunContentExpiry(String runId, Instant expiresAt);

    default int deleteContentForUser(String userId, String anonymizedUserId, Instant deletedAt) {
        return 0;
    }
}
