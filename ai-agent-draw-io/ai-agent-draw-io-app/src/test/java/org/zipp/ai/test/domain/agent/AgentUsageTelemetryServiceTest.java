package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
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
        AgentUsageTelemetryService service = new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC),
                new AgentUsageTelemetryService.TelemetryWriteExecutor() {
                    @Override public void execute(Runnable operation) { throw new RejectedExecutionException("full"); }
                    @Override public void shutdown() { }
                });

        service.startRun("usr_alice", "300000", "session-1", "chat",
                "PLATFORM", null, "openai", "gpt-5.5");

        assertTrue(store.runs.isEmpty());
    }

    private AgentUsageTelemetryService service(FakeAgentUsageTelemetryStore store) {
        return new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }
}
