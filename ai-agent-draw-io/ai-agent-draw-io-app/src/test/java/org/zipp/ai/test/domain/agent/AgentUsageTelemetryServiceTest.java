package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
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

    private AgentUsageTelemetryService service(FakeAgentUsageTelemetryStore store) {
        return new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC));
    }
}
