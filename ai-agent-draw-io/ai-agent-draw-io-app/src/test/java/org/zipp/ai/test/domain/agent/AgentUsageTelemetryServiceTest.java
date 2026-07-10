package org.zipp.ai.test.domain.agent;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.service.usage.AgentTelemetryMetrics;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class AgentUsageTelemetryServiceTest {

    @Test
    public void shouldStoreMissingProviderTokenCountsAsUnknownNotZero() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        AgentUsageTelemetryService.RunScope run = service.startRun(
                "usr_alice", "300000", "session-1", "chat",
                "PLATFORM", null, "openai", "gpt-5.5");

        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(run.getContext())) {
            service.recordLlmCall("routing", "openai", "gpt-5.5", 12L, null, null, null, null);
        }

        assertEquals(1, store.llmCalls.size());
        assertNull(store.llmCalls.get(0).getPromptTokens());
        assertNull(store.llmCalls.get(0).getCompletionTokens());
        assertNull(store.llmCalls.get(0).getTotalTokens());
    }

    @Test
    public void shouldSummarizePlatformAndUserKeyUsageForOneUser() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);

        service.startRun("usr_alice", "300000", "session-1", "chat",
                "PLATFORM", null, "openai", "gpt-5.5");
        service.startRun("usr_alice", "300000", "session-2", "chat",
                "USER_KEY", "mcr_alice", "openai", "gpt-4o");
        service.startRun("usr_bob", "300000", "session-3", "chat",
                "USER_KEY", "mcr_bob", "openai", "gpt-4o");

        assertEquals(Long.valueOf(1), store.summarizeForUser("usr_alice").getPlatformRunCount());
        assertEquals(Long.valueOf(1), store.summarizeForUser("usr_alice").getUserKeyRunCount());
    }

    @Test
    public void shouldStoreDiagramIdOnRunContextAndRunMetadata() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);

        AgentUsageTelemetryService.RunScope run = service.startRun(
                "aru_diagram", "req-diagram", "usr_alice", "300000", "session-1", "chat_stream",
                "diag_123", "PLATFORM", null, "openai", "gpt-5.5");

        assertEquals("diag_123", run.getContext().diagramId());
        assertEquals("diag_123", store.runs.get(0).getDiagramId());
    }

    @Test
    public void shouldRecordDiagramTraceSnapshotMetadataOnly() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        AgentUsageTelemetryService.RunScope run = service.startRun(
                "aru_snapshot", "req-snapshot", "usr_alice", "300000", "session-1", "chat_stream",
                "diag_snapshot", "PLATFORM", null, "openai", "gpt-5.5");
        CanvasState state = CanvasState.builder()
                .userId("usr_alice")
                .diagramId("diag_snapshot")
                .currentXml("<mxfile>raw xml must not be copied</mxfile>")
                .contentHash("hash-snapshot")
                .thumbnailUrl("data:image/png;base64,snapshot")
                .summary("UPDATED")
                .version(5L)
                .build();

        service.recordDiagramSnapshot(run.getContext().runId(), run.getContext().spanId(), state, "UPDATED");

        assertEquals(1, store.diagramSnapshots.size());
        AgentDiagramTraceSnapshot snapshot = store.diagramSnapshots.get(0);
        assertEquals("aru_snapshot", snapshot.getRunId());
        assertEquals("aru_snapshot", snapshot.getSpanId());
        assertEquals("diag_snapshot", snapshot.getDiagramId());
        assertEquals(Long.valueOf(5L), snapshot.getVersion());
        assertEquals("hash-snapshot", snapshot.getCanvasHash());
        assertEquals("data:image/png;base64,snapshot", snapshot.getThumbnailUrl());
        assertEquals("UPDATED", snapshot.getSummary());
        assertEquals(Instant.parse("2026-07-02T12:00:00Z"), snapshot.getCreatedAt());
        assertFalse(store.serializedRecords().contains("raw xml must not be copied"));
    }

    @Test
    public void shouldBackfillThumbnailOnlyForTheSnapshotWhoseCanvasHashMatches() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        service.recordDiagramSnapshot("aru_a", "span_a", canvasState("hash-A", null), "CREATED");
        service.recordDiagramSnapshot("aru_b", "span_b", canvasState("hash-B", null), "UPDATED");

        service.backfillDiagramSnapshotThumbnail("usr_alice", "diag_snapshot", "hash-A",
                "data:image/png;base64,exported");

        AgentDiagramTraceSnapshot matched = store.diagramSnapshots.stream()
                .filter(snapshot -> "hash-A".equals(snapshot.getCanvasHash())).findFirst().orElseThrow();
        AgentDiagramTraceSnapshot other = store.diagramSnapshots.stream()
                .filter(snapshot -> "hash-B".equals(snapshot.getCanvasHash())).findFirst().orElseThrow();
        assertEquals("data:image/png;base64,exported", matched.getThumbnailUrl());
        assertNull(other.getThumbnailUrl());
    }

    @Test
    public void shouldNotOverwriteAnExistingSnapshotThumbnailOnBackfill() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        service.recordDiagramSnapshot("aru_a", "span_a",
                canvasState("hash-A", "data:image/png;base64,original"), "CREATED");

        service.backfillDiagramSnapshotThumbnail("usr_alice", "diag_snapshot", "hash-A",
                "data:image/png;base64,exported");

        assertEquals("data:image/png;base64,original", store.diagramSnapshots.get(0).getThumbnailUrl());
    }

    private CanvasState canvasState(String contentHash, String thumbnailUrl) {
        return CanvasState.builder()
                .userId("usr_alice")
                .diagramId("diag_snapshot")
                .currentXml("<mxfile>raw</mxfile>")
                .contentHash(contentHash)
                .thumbnailUrl(thumbnailUrl)
                .version(1L)
                .build();
    }

    @Test
    public void shouldResolveRunContextByInvocationIdInsteadOfSessionId() {
        AgentUsageTelemetryService service = service(new FakeAgentUsageTelemetryStore());
        AgentUsageTelemetryService.RunScope first = service.startRun(
                "aru_first", "req-first", "usr_alice", "300000", "session-shared", "chat_stream",
                "PLATFORM", null, "openai", "gpt-5.5");
        AgentUsageTelemetryService.RunScope second = service.startRun(
                "aru_second", "req-second", "usr_alice", "300000", "session-shared", "chat_stream",
                "PLATFORM", null, "openai", "gpt-5.5");

        AgentUsageTelemetryContext.InvocationState firstState = AgentUsageTelemetryContext.newInvocationState(first.getContext());
        AgentUsageTelemetryContext.InvocationState secondState = AgentUsageTelemetryContext.newInvocationState(second.getContext());
        AgentUsageTelemetryContext.registerInvocation("invocation-a", firstState.stateDelta());
        AgentUsageTelemetryContext.registerInvocation("invocation-b", secondState.stateDelta());

        assertEquals("aru_first", AgentUsageTelemetryContext.resolveInvocation("invocation-a").orElseThrow().runId());
        assertEquals("aru_second", AgentUsageTelemetryContext.resolveInvocation("invocation-b").orElseThrow().runId());

        AgentUsageTelemetryContext.clearInvocation("invocation-a");
        firstState.close();

        assertTrue(AgentUsageTelemetryContext.resolveInvocation("invocation-a").isEmpty());
        assertEquals("aru_second", AgentUsageTelemetryContext.resolveInvocation("invocation-b").orElseThrow().runId());

        AgentUsageTelemetryContext.clearInvocation("invocation-b");
        secondState.close();

        AgentUsageTelemetryContext.InvocationState orphanState = AgentUsageTelemetryContext.newInvocationState(first.getContext());
        AgentUsageTelemetryContext.registerInvocation("invocation-orphan", orphanState.stateDelta());
        orphanState.close();

        assertTrue(AgentUsageTelemetryContext.resolveInvocation("invocation-orphan").isEmpty());
    }

    @Test
    public void shouldRecordLifecycleTraceEventsWithPerRunSequenceAndSanitizedMetadata() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        AgentUsageTelemetryService.RunScope run = service.startRun(
                "aru_trace", "req-trace", "usr_alice", "300000", "session-1", "chat_stream",
                "PLATFORM", null, "openai", "gpt-5.5");

        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(run.getContext())) {
            service.recordTraceEvent("HTTP_REQUEST_RECEIVED", "request", "SUCCESS",
                    Map.of("messageChars", 15, "sample", "sk-live-secret"));
            service.recordTraceEvent("STREAM_META_SENT", "stream", "SUCCESS",
                    Map.of("chunkCount", 0));
        }

        assertEquals(2, store.traceEvents.size());
        AgentTraceEvent first = store.traceEvents.get(0);
        AgentTraceEvent second = store.traceEvents.get(1);
        assertEquals("aru_trace", first.getRunId());
        assertEquals("req-trace", first.getRequestId());
        assertEquals(Long.valueOf(1), first.getSequenceNo());
        assertEquals(Long.valueOf(2), second.getSequenceNo());
        assertTrue(first.getMetadataJson().contains("messageChars"));
        assertFalse(first.getMetadataJson().contains("sk-live-secret"));
    }

    @Test
    public void shouldLinkStepsToRunAndCallsToTheEnclosingStep() throws Exception {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        AgentUsageTelemetryService.RunScope run = service.startRun(
                "aru_tree", "req-tree", "usr_alice", "300000", "session-1", "chat",
                "PLATFORM", null, "openai", "gpt-5.5");

        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(run.getContext())) {
            // A run-level lifecycle event hangs directly under the run.
            service.recordTraceEvent("HTTP_REQUEST_RECEIVED", "request", "SUCCESS", Map.of("k", "v"));
            // Calls made inside a step parent onto that step, not onto the run.
            service.recordStep("drawing", () -> {
                AgentUsageTelemetryContext.RunContext stepCtx =
                        AgentUsageTelemetryContext.current().orElseThrow();
                service.recordLlmCall("drawing", "openai", "gpt-5.5", 12L, 1, 2, 3, null);
                service.recordToolCall(stepCtx, "drawing", "draw_canvas", 5L, null);
                return null;
            });
        }

        assertEquals(1, store.steps.size());
        assertEquals(1, store.llmCalls.size());
        assertEquals(1, store.toolCalls.size());
        assertEquals(1, store.traceEvents.size());

        String stepId = store.steps.get(0).getId();
        assertTrue(stepId.startsWith("ars_"));
        // Step -> run
        assertEquals("aru_tree", store.steps.get(0).getParentId());
        // LLM call / tool call -> enclosing step
        assertEquals(stepId, store.llmCalls.get(0).getParentId());
        assertEquals(stepId, store.toolCalls.get(0).getParentId());
        // Run-level trace event -> run
        assertEquals("aru_tree", store.traceEvents.get(0).getParentId());
    }

    @Test
    public void shouldDelegateTelemetryRetentionCleanupToStore() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService service = service(store);
        Instant cutoff = Instant.parse("2026-06-01T00:00:00Z");

        store.deletedBeforeCount = 7;
        int deleted = service.deleteTelemetryBefore(cutoff);

        assertEquals(7, deleted);
        assertEquals(cutoff, store.deletedBeforeCutoff);
    }

    @Test
    public void shouldNotFailRequestWhenTelemetryQueueIsFull() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentUsageTelemetryService service = new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
                new AgentUsageTelemetryService.TelemetryWriteExecutor() {
                    @Override public void execute(Runnable operation) { throw new RejectedExecutionException("full"); }
                    @Override public void shutdown() { }
                },
                new AgentTelemetryMetrics(registry));

        service.startRun("usr_alice", "300000", "session-1", "chat",
                "PLATFORM", null, "openai", "gpt-5.5");

        assertTrue(store.runs.isEmpty());
        assertEquals(1D, registry.get("ai.agent.telemetry.write.dropped").counter().count(), 0.001D);
    }

    @Test
    public void shouldPublishUsageMetricsWithoutHighCardinalityLabels() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentUsageTelemetryService service = new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
                AgentUsageTelemetryService.TelemetryWriteExecutor.direct(),
                new AgentTelemetryMetrics(registry));
        AgentUsageTelemetryService.RunScope run = service.startRun(
                "aru_metric", "req-secret", "usr_alice", "300000", "session-1", "chat_stream",
                "USER_KEY", "mcr_secret", "openai", "gpt-5.5");

        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(run.getContext())) {
            service.recordLlmCall("routing", "openai", "gpt-5.5", 50L, 10, 20, 30, null);
            service.recordToolCall(run.getContext(), "drawing", "draw_canvas", 25L, new IllegalStateException("bad tool"));
        }
        service.completeRun(run, null);

        assertEquals(1D, registry.get("ai.agent.run")
                .tag("request_type", "chat_stream")
                .tag("credential_source", "user_key")
                .tag("status", "success")
                .counter().count(), 0.001D);
        assertEquals(1L, registry.get("ai.agent.run.latency").timer().count());
        assertEquals(1D, registry.get("ai.agent.llm.call")
                .tag("phase", "routing")
                .tag("status", "success")
                .counter().count(), 0.001D);
        assertEquals(30D, registry.get("ai.agent.llm.tokens")
                .tag("token_type", "total")
                .counter().count(), 0.001D);
        assertEquals(1D, registry.get("ai.agent.tool.call")
                .tag("tool_name", "draw_canvas")
                .tag("status", "failed")
                .counter().count(), 0.001D);

        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getKey().equals("user_id"));
                assertFalse(tag.getKey().equals("run_id"));
                assertFalse(tag.getKey().equals("request_id"));
                assertFalse(tag.getKey().equals("session_id"));
                assertFalse(tag.getKey().equals("model_credential_id"));
                assertFalse(tag.getValue().contains("usr_alice"));
                assertFalse(tag.getValue().contains("aru_metric"));
                assertFalse(tag.getValue().contains("req-secret"));
                assertFalse(tag.getValue().contains("mcr_secret"));
            });
        }
    }

    @Test
    public void shouldBucketUserSuppliedModelToCustomButKeepPlatformModel() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentUsageTelemetryService service = new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
                AgentUsageTelemetryService.TelemetryWriteExecutor.direct(),
                new AgentTelemetryMetrics(registry));

        // User-supplied credential: an arbitrary per-request model string must collapse to "custom"
        // so it cannot blow up Prometheus series cardinality.
        AgentUsageTelemetryService.RunScope userRun = service.startRun(
                "aru_user", "req-1", "usr_alice", "300000", "session-user", "chat_stream",
                "USER_KEY", "mcr_secret", "openai", "some-random-user-model");
        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(userRun.getContext())) {
            service.recordLlmCall("routing", "openai", "some-random-user-model", 50L, 10, 20, 30, null);
        }

        // Platform credential: operator-controlled and bounded, so the real model is preserved.
        AgentUsageTelemetryService.RunScope platformRun = service.startRun(
                "aru_plat", "req-2", "usr_bob", "300000", "session-plat", "chat_stream",
                "PLATFORM", null, "openai", "gpt-5.5");
        try (AgentUsageTelemetryContext.Scope ignored = AgentUsageTelemetryContext.bind(platformRun.getContext())) {
            service.recordLlmCall("routing", "openai", "gpt-5.5", 50L, 10, 20, 30, null);
        }

        assertEquals(1D, registry.get("ai.agent.llm.call")
                .tag("credential_source", "user_key")
                .tag("model", "custom")
                .counter().count(), 0.001D);
        assertEquals(1D, registry.get("ai.agent.llm.call")
                .tag("credential_source", "platform")
                .tag("model", "gpt-5.5")
                .counter().count(), 0.001D);

        // The arbitrary user model string must never surface as a metric tag value.
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag ->
                    assertFalse(tag.getValue().contains("some-random-user-model")));
        }
    }

    private AgentUsageTelemetryService service(FakeAgentUsageTelemetryStore store) {
        return new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }
}
