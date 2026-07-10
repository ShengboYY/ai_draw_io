package org.zipp.ai.test.domain.agent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.AdminAuditLogService;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl;
import org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTracePayloadKind;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.AgentTelemetryMetrics;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AgentDebugTraceServiceTest {

    private MutableClock clock;
    private FakeDebugTraceStore traceStore;
    private FakeAdminAuditLogStore auditStore;
    private SimpleMeterRegistry registry;
    private AgentDebugTraceService service;

    @Before
    public void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-03T10:00:00Z"));
        traceStore = new FakeDebugTraceStore();
        auditStore = new FakeAdminAuditLogStore();
        registry = new SimpleMeterRegistry();
        service = new AgentDebugTraceService(
                traceStore,
                new AdminAuditLogService(auditStore, clock),
                clock,
                new AgentTelemetryMetrics(registry));
    }

    @Test
    public void captureIsDisabledByDefault() {
        Optional<DebugTraceCapture> captured = service.capture(
                "usr_alice", "aru_1", "CHAT_REQUEST", "secret prompt");

        assertTrue(captured.isEmpty());
        assertTrue(traceStore.captures.isEmpty());
    }

    @Test
    public void enableControlRequiresANarrowScope() {
        try {
            service.enableControl("usr_admin", null, null, null, null);
            throw new AssertionError("expected unscoped debug trace control to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("scope"));
        }
    }

    @Test
    public void userScopedControlCapturesOnlyThatUserWithSevenDayExpiry() {
        service.enableControl("usr_admin", "usr_alice", null, null, null);

        Optional<DebugTraceCapture> ignored = service.capture(
                "usr_bob", "aru_bob", "CHAT_REQUEST", "bob prompt");
        Optional<DebugTraceCapture> captured = service.capture(
                "usr_alice", "aru_alice", "CHAT_REQUEST", "alice prompt");

        assertTrue(ignored.isEmpty());
        assertTrue(captured.isPresent());
        assertEquals("usr_alice", captured.get().getUserId());
        assertEquals("aru_alice", captured.get().getRunId());
        assertEquals("CHAT_REQUEST", captured.get().getEventType());
        assertEquals("alice prompt", captured.get().getContent());
        assertNotNull(captured.get().getContentSha256());
        assertEquals(clock.instant().plus(Duration.ofDays(7)), captured.get().getContentExpiresAt());
    }

    @Test
    public void spanPayloadKeepsItsSpanKindAndTruncationEvidence() {
        service.enableControl("usr_admin", null, "aru_span", null, null);
        String oversizedJson = "x".repeat(64_010);

        DebugTraceCapture capture = service.captureSpanPayload(
                "usr_alice",
                "aru_span",
                "alc_1",
                DebugTracePayloadKind.OUTPUT,
                "application/json",
                oversizedJson).orElseThrow();

        assertEquals("alc_1", capture.getSpanId());
        assertEquals("OUTPUT", capture.getPayloadKind());
        assertEquals("application/json", capture.getContentType());
        assertEquals(Long.valueOf(64_010), capture.getOriginalLength());
        assertTrue(capture.isTruncated());
        assertEquals(64_000, capture.getContent().length());
    }

    @Test
    public void spanPayloadAcceptsTheTrueOriginalLengthOfABoundedUpstreamCapture() {
        service.enableControl("usr_admin", null, "aru_bounded", null, null);

        DebugTraceCapture capture = service.captureSpanPayload(
                "usr_alice", "aru_bounded", "alc_bounded", DebugTracePayloadKind.OUTPUT, "application/json",
                "retained prefix", 125_000L).orElseThrow();

        assertEquals(Long.valueOf(125_000), capture.getOriginalLength());
        assertTrue(capture.isTruncated());
        assertEquals("retained prefix", capture.getContent());
    }

    @Test
    public void captureRedactsJsonSecretsAndHiddenThoughtPartsBeforePersistence() {
        service.enableControl("usr_admin", null, "aru_redact", null, null);
        String payload = """
                {"authorization":"Bearer secret-token","apiKey":"sk-live-123","thoughtsTokenCount":42,
                 "parts":[{"thought":true,"text":"private chain of thought"},{"text":"safe answer"}]}
                """;

        DebugTraceCapture capture = service.captureSpanPayload(
                "usr_alice", "aru_redact", "alc_redact", DebugTracePayloadKind.OUTPUT, "application/json", payload)
                .orElseThrow();

        assertFalse(capture.getContent().contains("secret-token"));
        assertFalse(capture.getContent().contains("sk-live-123"));
        assertFalse(capture.getContent().contains("private chain of thought"));
        assertTrue(capture.getContent().contains("[REDACTED]"));
        assertTrue(capture.getContent().contains("thoughtsTokenCount"));
        assertTrue(capture.getContent().contains("safe answer"));
    }

    @Test
    public void captureRedactsSecretsEmbeddedInsideNestedTextValues() {
        service.enableControl("usr_admin", null, "aru_nested_secret", null, null);
        String payload = """
                {"error":{"message":"provider rejected Authorization: Bearer bearer-secret-123"},
                 "content":{"parts":[{"text":"do not retain sk-live-secret-123456"}]}}
                """;

        DebugTraceCapture capture = service.captureSpanPayload(
                "usr_alice", "aru_nested_secret", "alc_nested", DebugTracePayloadKind.ERROR,
                "application/json", payload).orElseThrow();

        assertFalse(capture.getContent().contains("bearer-secret-123"));
        assertFalse(capture.getContent().contains("sk-live-secret-123456"));
        assertTrue(capture.getContent().contains("provider rejected"));
        assertTrue(capture.getContent().contains("do not retain"));
    }

    @Test
    public void runAndTimeWindowScopesMustBothMatch() {
        service.enableControl(
                "usr_admin",
                null,
                "aru_target",
                clock.instant().minus(Duration.ofMinutes(5)),
                clock.instant().plus(Duration.ofMinutes(5)));

        assertTrue(service.capture("usr_alice", "aru_other", "CHAT_REQUEST", "ignored").isEmpty());
        assertTrue(service.capture("usr_alice", "aru_target", "CHAT_REQUEST", "captured").isPresent());

        clock.advance(Duration.ofMinutes(6));

        assertTrue(service.capture("usr_alice", "aru_target", "CHAT_REQUEST", "expired scope").isEmpty());
    }

    @Test
    public void cleanupDeletesExpiredContentButKeepsMetadata() {
        service.enableControl("usr_admin", "usr_alice", null, null, null);
        DebugTraceCapture capture = service.capture(
                "usr_alice", "aru_1", "CHAT_REQUEST", "sensitive prompt").orElseThrow();

        clock.advance(Duration.ofDays(8));
        int cleaned = service.cleanupExpiredContent();

        assertEquals(1, cleaned);
        assertEquals("aru_1", capture.getRunId());
        assertEquals("CHAT_REQUEST", capture.getEventType());
        assertEquals(null, capture.getContent());
        assertEquals(clock.instant(), capture.getContentDeletedAt());
    }

    @Test
    public void viewingExpiredContentHidesItBeforeScheduledCleanupRuns() {
        service.enableControl("usr_admin", null, "aru_expired", null, null);
        service.capture("usr_alice", "aru_expired", "CHAT_REQUEST", "expired secret").orElseThrow();
        clock.advance(Duration.ofDays(8));

        List<DebugTraceCapture> captures = service.viewCapturesForRun(
                "usr_admin", "aru_expired", "203.0.113.20", "JUnit");

        assertEquals(1, captures.size());
        assertEquals(null, captures.get(0).getContent());
        assertEquals(null, captures.get(0).getContentDeletedAt());
    }

    @Test
    public void retentionExtensionIsRunSpecificAndAudited() {
        service.enableControl("usr_admin", null, "aru_keep", null, null);
        service.capture("usr_alice", "aru_keep", "CHAT_REQUEST", "keep me").orElseThrow();
        service.capture("usr_alice", "aru_other", "CHAT_REQUEST", "ignore me");

        Instant extendedUntil = clock.instant().plus(Duration.ofDays(30));
        int extended = service.extendRetentionForRun(
                "usr_admin", "aru_keep", extendedUntil, "203.0.113.10", "JUnit");

        assertEquals(1, extended);
        assertEquals(extendedUntil, traceStore.captures.get(0).getContentExpiresAt());
        assertEquals(1, auditStore.logs.size());
        assertEquals("EXTEND_DEBUG_TRACE_RETENTION", auditStore.logs.get(0).getAction());
        assertEquals("RUN", auditStore.logs.get(0).getTargetType());
        assertEquals("aru_keep", auditStore.logs.get(0).getTargetId());
    }

    @Test
    public void viewingCapturedDebugTraceContentIsAudited() {
        service.enableControl("usr_admin", null, "aru_view", null, null);
        service.capture("usr_alice", "aru_view", "CHAT_REQUEST", "prompt with canvas").orElseThrow();

        List<DebugTraceCapture> captures = service.viewCapturesForRun(
                "usr_admin", "aru_view", "203.0.113.20", "JUnit");

        assertEquals(1, captures.size());
        assertEquals("prompt with canvas", captures.get(0).getContent());
        assertEquals(1, auditStore.logs.size());
        assertEquals("VIEW_DEBUG_TRACE_CAPTURE", auditStore.logs.get(0).getAction());
        assertEquals("RUN", auditStore.logs.get(0).getTargetType());
        assertEquals("aru_view", auditStore.logs.get(0).getTargetId());
        assertEquals("SUCCESS", auditStore.logs.get(0).getOutcome());
        assertEquals(1D, registry.get("ai.agent.debug.trace.capture")
                .tag("event_type", "chat_request")
                .counter().count(), 0.001D);
        assertEquals(1D, registry.get("ai.agent.debug.trace.view")
                .tag("outcome", "success")
                .counter().count(), 0.001D);
    }

    @Test
    public void mapperXmlAnonymizesTraceMetadataAfterContentWasAlreadyDeleted() throws Exception {
        String mapperXml = new String(getClass()
                .getResourceAsStream("/mybatis/mapper/agent_debug_trace_mapper.xml")
                .readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertTrue(mapperXml.contains("SET user_id = #{anonymizedUserId}"));
        assertTrue(mapperXml.contains("content_deleted_at = CASE"));
        assertTrue(mapperXml.contains("ELSE content_deleted_at"));
        assertFalse(mapperXml.contains("WHERE user_id = #{userId} AND content IS NOT NULL"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private final List<DebugTraceControl> controls = new ArrayList<>();
        private final List<DebugTraceCapture> captures = new ArrayList<>();

        @Override
        public void insertControl(DebugTraceControl control) {
            controls.add(control);
        }

        @Override
        public List<DebugTraceControl> listEnabledControls() {
            return controls.stream()
                    .filter(DebugTraceControl::isEnabled)
                    .toList();
        }

        @Override
        public void insertCapture(DebugTraceCapture capture) {
            captures.add(capture);
        }

        @Override
        public int deleteExpiredContent(Instant now) {
            int count = 0;
            for (DebugTraceCapture capture : captures) {
                if (capture.getContent() != null && !capture.getContentExpiresAt().isAfter(now)) {
                    capture.setContent(null);
                    capture.setContentDeletedAt(now);
                    count++;
                }
            }
            return count;
        }

        @Override
        public int extendRunContentExpiry(String runId, Instant expiresAt) {
            int count = 0;
            for (DebugTraceCapture capture : captures) {
                if (runId.equals(capture.getRunId()) && capture.getContent() != null) {
                    capture.setContentExpiresAt(expiresAt);
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<DebugTraceCapture> listCapturesByRunId(String runId) {
            return captures.stream()
                    .filter(capture -> runId.equals(capture.getRunId()))
                    .toList();
        }
    }

    private static final class FakeAdminAuditLogStore implements IAdminAuditLogStore {
        private final List<AdminAuditLog> logs = new ArrayList<>();

        @Override
        public void insert(AdminAuditLog log) {
            logs.add(log);
        }

        @Override
        public List<AdminAuditLog> listRecent(int limit) {
            return logs.stream().limit(limit).toList();
        }
    }
}
