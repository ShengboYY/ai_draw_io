package org.zipp.ai.test.app;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTracePort;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;
import org.zipp.ai.infrastructure.turn.agent.LoggingPlainAgentTraceAdapter;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingPlainAgentTraceAdapterTest {

    @Test
    void logsCompactAgentFactsWithoutUnsafeEventContent() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingPlainAgentTraceAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new LoggingPlainAgentTraceAdapter(PlainAgentTracePort.NOOP).record(
                    new PlainAgentTraceEvent(
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
            new LoggingPlainAgentTraceAdapter(PlainAgentTracePort.NOOP).record(
                    new PlainAgentTraceEvent(
                            attempt(),
                            3,
                            PlainAgentTraceType.AGENT_STOPPED,
                            "unsafe <mxGraphModel>",
                            "",
                            "",
                            "",
                            "",
                            "unsafe <mxGraphModel>",
                            0,
                            0,
                            Instant.parse("2026-07-28T00:00:01Z")));

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("[plain-agent] event=tool_completed")
                    .contains("attemptId=attempt-1")
                    .contains("step=2")
                    .contains("tool=patch_draft")
                    .contains("outcome=SUCCESS")
                    .contains("issueCount=1")
                    .doesNotContain("mxGraphModel");
            assertThat(appender.list.get(1).getFormattedMessage())
                    .contains("event=agent_stopped")
                    .contains("action=redacted")
                    .contains("outcome=redacted")
                    .doesNotContain("mxGraphModel");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private FencedAttempt attempt() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, now.plusSeconds(60), 60_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(
                        1,
                        TurnEngineMode.V2_CANARY,
                        "{}",
                        "policy-hash"));
    }
}
