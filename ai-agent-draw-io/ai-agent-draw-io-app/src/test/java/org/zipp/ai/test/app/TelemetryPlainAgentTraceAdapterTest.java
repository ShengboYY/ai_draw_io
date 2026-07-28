package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.infrastructure.turn.agent.TelemetryPlainAgentTraceAdapter;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryPlainAgentTraceAdapterTest {

    @Test
    void recordsOnlyRedactedAgentMetadataUnderTheCurrentRun() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        AgentUsageTelemetryService telemetry = new AgentUsageTelemetryService(
                store,
                Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC));
        var context = new AgentUsageTelemetryContext.RunContext(
                "aru_plain_agent",
                "turn-1",
                "diagram-1",
                "owner-1",
                "300025",
                "chat",
                AgentUsageTelemetryService.PLATFORM,
                null,
                "openai",
                "gpt-5.5",
                "turn_v2_execution");

        try (AgentUsageTelemetryContext.Scope ignored =
                     AgentUsageTelemetryContext.bind(context)) {
            new TelemetryPlainAgentTraceAdapter(telemetry).record(new PlainAgentTraceEvent(
                    attempt(),
                    2,
                    PlainAgentTraceType.TOOL_COMPLETED,
                    "CALL_TOOL",
                    "patch_draft",
                    "a".repeat(64),
                    "sha256:" + "b".repeat(64),
                    "sha256:" + "c".repeat(64),
                    "SUCCESS",
                    1,
                    12,
                    Instant.parse("2026-07-28T00:00:00Z")));
        }

        assertThat(store.traceEvents).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("tool_completed");
            assertThat(event.getPhase()).isEqualTo("plain_agent");
            assertThat(event.getStatus()).isEqualTo("SUCCESS");
            assertThat(event.getMetadataJson())
                    .contains("\"toolName\":\"patch_draft\"")
                    .contains("\"issueCount\":1")
                    .doesNotContain("mxGraphModel")
                    .doesNotContain("assistantMessage");
        });
    }

    private FencedAttempt attempt() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, now.plusSeconds(60), 60_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy-hash"));
    }
}
