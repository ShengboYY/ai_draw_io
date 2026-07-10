package org.zipp.ai.domain.agent.service.debugtrace;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTracePayloadKind;
import org.zipp.ai.domain.agent.service.usage.AgentTelemetryMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentDebugTraceService {

    static final Duration DEFAULT_CONTENT_TTL = Duration.ofDays(7);
    private static final int MAX_EVENT_TYPE_LENGTH = 64;
    private static final int MAX_CONTENT_LENGTH = 64_000;

    private final IAgentDebugTraceStore debugTraceStore;
    private final AdminAuditLogService auditLogService;
    private final Clock clock;
    private final AgentTelemetryMetrics metrics;

    @Autowired
    public AgentDebugTraceService(IAgentDebugTraceStore debugTraceStore,
                                  AdminAuditLogService auditLogService,
                                  AgentTelemetryMetrics metrics) {
        this(debugTraceStore, auditLogService, Clock.systemUTC(), metrics);
    }

    public AgentDebugTraceService(IAgentDebugTraceStore debugTraceStore,
                                  AdminAuditLogService auditLogService) {
        this(debugTraceStore, auditLogService, Clock.systemUTC(), AgentTelemetryMetrics.noop());
    }

    public AgentDebugTraceService(IAgentDebugTraceStore debugTraceStore,
                                  AdminAuditLogService auditLogService,
                                  Clock clock) {
        this(debugTraceStore, auditLogService, clock, AgentTelemetryMetrics.noop());
    }

    public AgentDebugTraceService(IAgentDebugTraceStore debugTraceStore,
                                  AdminAuditLogService auditLogService,
                                  Clock clock,
                                  AgentTelemetryMetrics metrics) {
        this.debugTraceStore = debugTraceStore;
        this.auditLogService = auditLogService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.metrics = metrics == null ? AgentTelemetryMetrics.noop() : metrics;
    }

    public DebugTraceControl enableControl(String actorUserId,
                                           String scopeUserId,
                                           String scopeRunId,
                                           Instant scopeStartsAt,
                                           Instant scopeEndsAt) {
        String actor = requireText(actorUserId, "actorUserId", 64);
        String userScope = trimToNull(scopeUserId, 64);
        String runScope = trimToNull(scopeRunId, 64);
        validateScope(userScope, runScope, scopeStartsAt, scopeEndsAt);
        DebugTraceControl control = DebugTraceControl.builder()
                .id("dtc_" + UUID.randomUUID())
                .createdByUserId(actor)
                .scopeUserId(userScope)
                .scopeRunId(runScope)
                .scopeStartsAt(scopeStartsAt)
                .scopeEndsAt(scopeEndsAt)
                .enabled(true)
                .createdAt(clock.instant())
                .build();
        debugTraceStore.insertControl(control);
        return control;
    }

    public Optional<DebugTraceCapture> capture(String userId, String runId, String eventType, String content) {
        return capturePayload(userId, runId, null, eventType, eventType, "text/plain", content,
                content == null ? 0L : content.length());
    }

    public Optional<DebugTraceCapture> captureSpanPayload(String userId,
                                                          String runId,
                                                          String spanId,
                                                          DebugTracePayloadKind payloadKind,
                                                          String contentType,
                                                          String content) {
        return captureSpanPayload(userId, runId, spanId, payloadKind, contentType, content,
                content == null ? 0L : content.length());
    }

    public Optional<DebugTraceCapture> captureSpanPayload(String userId,
                                                          String runId,
                                                          String spanId,
                                                          DebugTracePayloadKind payloadKind,
                                                          String contentType,
                                                          String content,
                                                          long originalLength) {
        if (payloadKind == null) {
            return Optional.empty();
        }
        return capturePayload(userId, runId, spanId, payloadKind.name(), payloadKind.name(),
                contentType, content, originalLength);
    }

    private Optional<DebugTraceCapture> capturePayload(String userId,
                                                       String runId,
                                                       String spanId,
                                                       String eventType,
                                                       String payloadKind,
                                                       String contentType,
                                                       String content,
                                                       long originalLength) {
        if (debugTraceStore == null || StringUtils.isBlank(content)) {
            return Optional.empty();
        }
        String owner = trimToNull(userId, 64);
        String run = trimToNull(runId, 64);
        String span = trimToNull(spanId, 64);
        String type = trimToNull(eventType, MAX_EVENT_TYPE_LENGTH);
        String kind = trimToNull(payloadKind, MAX_EVENT_TYPE_LENGTH);
        String mediaType = trimToNull(contentType, 64);
        if (owner == null || run == null || type == null || kind == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<DebugTraceControl> control = activeControl(owner, run, now);
        if (control.isEmpty()) {
            return Optional.empty();
        }
        // Keep truncation evidence so the inspector never presents a partial payload as complete.
        long effectiveOriginalLength = Math.max(content.length(), originalLength);
        String redactedContent = DebugTraceContentRedactor.redact(content, mediaType);
        String storedContent = StringUtils.left(redactedContent, MAX_CONTENT_LENGTH);
        DebugTraceCapture capture = DebugTraceCapture.builder()
                .id("adt_" + UUID.randomUUID())
                .controlId(control.get().getId())
                .userId(owner)
                .runId(run)
                .spanId(span)
                .eventType(type)
                .payloadKind(kind)
                .contentType(StringUtils.defaultIfBlank(mediaType, "text/plain"))
                .content(storedContent)
                .contentSha256(sha256(storedContent))
                .originalLength(effectiveOriginalLength)
                .truncated(effectiveOriginalLength > content.length()
                        || content.length() > MAX_CONTENT_LENGTH
                        || redactedContent.length() > storedContent.length())
                .contentExpiresAt(now.plus(DEFAULT_CONTENT_TTL))
                .createdAt(now)
                .build();
        debugTraceStore.insertCapture(capture);
        metrics.recordDebugTraceCapture(type);
        return Optional.of(capture);
    }

    public int cleanupExpiredContent() {
        return debugTraceStore == null ? 0 : debugTraceStore.deleteExpiredContent(clock.instant());
    }

    public List<DebugTraceCapture> viewCapturesForRun(String actorUserId,
                                                      String runId,
                                                      String ipAddress,
                                                      String userAgent) {
        String actor = requireText(actorUserId, "actorUserId", 64);
        String run = requireText(runId, "runId", 64);
        List<DebugTraceCapture> captures = debugTraceStore == null
                ? List.of()
                : debugTraceStore.listCapturesByRunId(run);
        List<DebugTraceCapture> safeCaptures = hideExpiredContent(captures);
        if (auditLogService != null) {
            auditLogService.record(actor, "VIEW_DEBUG_TRACE_CAPTURE", "RUN", run,
                    safeCaptures.isEmpty() ? "NOT_FOUND" : "SUCCESS", ipAddress, userAgent);
        }
        metrics.recordDebugTraceView(safeCaptures.isEmpty() ? "NOT_FOUND" : "SUCCESS");
        return safeCaptures;
    }

    public List<DebugTraceCapture> viewCapturesForSpan(String actorUserId,
                                                       String runId,
                                                       String spanId,
                                                       String ipAddress,
                                                       String userAgent) {
        String actor = requireText(actorUserId, "actorUserId", 64);
        String run = requireText(runId, "runId", 64);
        String span = requireText(spanId, "spanId", 64);
        List<DebugTraceCapture> captures = debugTraceStore == null
                ? List.of()
                : debugTraceStore.listCapturesByRunAndSpanId(run, span);
        List<DebugTraceCapture> safeCaptures = hideExpiredContent(captures);
        if (auditLogService != null) {
            auditLogService.record(actor, "VIEW_DEBUG_TRACE_SPAN_PAYLOAD", "SPAN", span,
                    safeCaptures.isEmpty() ? "NOT_FOUND" : "SUCCESS", ipAddress, userAgent);
        }
        metrics.recordDebugTraceView(safeCaptures.isEmpty() ? "NOT_FOUND" : "SUCCESS");
        return safeCaptures;
    }

    private List<DebugTraceCapture> hideExpiredContent(List<DebugTraceCapture> captures) {
        if (captures == null || captures.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        captures.forEach(capture -> {
            // Enforce retention at read time even if the scheduled cleanup has not run yet.
            if (capture != null && capture.getContentExpiresAt() != null
                    && !capture.getContentExpiresAt().isAfter(now)) {
                capture.setContent(null);
            }
        });
        return captures;
    }

    public int extendRetentionForRun(String actorUserId,
                                     String runId,
                                     Instant expiresAt,
                                     String ipAddress,
                                     String userAgent) {
        String actor = requireText(actorUserId, "actorUserId", 64);
        String run = requireText(runId, "runId", 64);
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future.");
        }
        int extended = debugTraceStore == null ? 0 : debugTraceStore.extendRunContentExpiry(run, expiresAt);
        if (auditLogService != null) {
            auditLogService.record(actor, "EXTEND_DEBUG_TRACE_RETENTION", "RUN", run,
                    extended > 0 ? "SUCCESS" : "NOT_FOUND", ipAddress, userAgent);
        }
        return extended;
    }

    private Optional<DebugTraceControl> activeControl(String userId, String runId, Instant capturedAt) {
        List<DebugTraceControl> controls = debugTraceStore.listEnabledControls();
        if (controls == null || controls.isEmpty()) {
            return Optional.empty();
        }
        return controls.stream()
                .filter(control -> matches(control, userId, runId, capturedAt))
                .findFirst();
    }

    private boolean matches(DebugTraceControl control, String userId, String runId, Instant capturedAt) {
        if (control == null || !control.isEnabled() || control.getDisabledAt() != null) {
            return false;
        }
        if (StringUtils.isNotBlank(control.getScopeUserId()) && !control.getScopeUserId().equals(userId)) {
            return false;
        }
        if (StringUtils.isNotBlank(control.getScopeRunId()) && !control.getScopeRunId().equals(runId)) {
            return false;
        }
        if (control.getScopeStartsAt() != null && capturedAt.isBefore(control.getScopeStartsAt())) {
            return false;
        }
        return control.getScopeEndsAt() == null || !capturedAt.isAfter(control.getScopeEndsAt());
    }

    private void validateScope(String scopeUserId, String scopeRunId, Instant startsAt, Instant endsAt) {
        boolean hasUserScope = scopeUserId != null;
        boolean hasRunScope = scopeRunId != null;
        boolean hasTimeScope = startsAt != null || endsAt != null;
        if (!hasUserScope && !hasRunScope && !hasTimeScope) {
            throw new IllegalArgumentException("debug trace control requires a user, run, or time scope.");
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("scopeEndsAt must be after scopeStartsAt.");
        }
        // A time-only control must be a true bounded window, never an open-ended global trace.
        if (!hasUserScope && !hasRunScope && (startsAt == null || endsAt == null)) {
            throw new IllegalArgumentException("time-only debug trace control requires both start and end.");
        }
    }

    private String requireText(String value, String fieldName, int maxLength) {
        String trimmed = trimToNull(value, maxLength);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return trimmed;
    }

    private String trimToNull(String value, int maxLength) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException("value is too long.");
        }
        return trimmed;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
