package org.zipp.ai.test.trigger.job;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.job.TelemetryRetentionJob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;

public class TelemetryRetentionJobTest {

    @Test
    public void purgeDeletesTelemetryOlderThanRetentionWindow() {
        FakeAgentUsageTelemetryStore store = new FakeAgentUsageTelemetryStore();
        Clock clock = Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC);
        AgentUsageTelemetryService telemetry = new AgentUsageTelemetryService(
                store, clock, AgentUsageTelemetryService.TelemetryWriteExecutor.direct());
        // Null stores keep the debug cleanup path a safe no-op; this test targets the cutoff math.
        AgentDebugTraceService debug = new AgentDebugTraceService(null, null, clock);
        TelemetryRetentionJob job = new TelemetryRetentionJob(debug, telemetry, clock);

        job.purgeExpiredTelemetry();

        // retentionDays is 0 outside Spring, so the job floors it to 1 day before "now".
        assertEquals(Instant.parse("2026-07-08T00:00:00Z"), store.deletedBeforeCutoff);
    }

    @Test
    public void purgeSwallowsFailuresSoTheScheduleKeepsRunning() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-09T00:00:00Z"), ZoneOffset.UTC);
        FakeAgentUsageTelemetryStore throwingStore = new FakeAgentUsageTelemetryStore() {
            @Override
            public int deleteTelemetryBefore(Instant cutoff) {
                throw new RuntimeException("boom");
            }
        };
        AgentUsageTelemetryService telemetry = new AgentUsageTelemetryService(
                throwingStore, clock, AgentUsageTelemetryService.TelemetryWriteExecutor.direct());
        AgentDebugTraceService debug = new AgentDebugTraceService(null, null, clock);

        // A failing delete must not escape the scheduled method, or the schedule dies silently.
        new TelemetryRetentionJob(debug, telemetry, clock).purgeExpiredTelemetry();
    }
}
