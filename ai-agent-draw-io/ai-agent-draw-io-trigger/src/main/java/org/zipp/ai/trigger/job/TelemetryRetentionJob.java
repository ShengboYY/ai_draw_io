package org.zipp.ai.trigger.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Enforces retention for observability data: purges debug-trace content past its TTL and drops
 * usage-telemetry rows (runs/steps/llm/tool/trace events) older than the configured window.
 * Both delete paths are idempotent, so running on several instances is redundant but harmless.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "zipp.telemetry.cleanup-enabled", havingValue = "true")
public class TelemetryRetentionJob {

    private final AgentDebugTraceService agentDebugTraceService;
    private final AgentUsageTelemetryService agentUsageTelemetryService;
    private final Clock clock;

    @Value("${zipp.telemetry.retention-days:30}")
    private int retentionDays;

    @Autowired
    public TelemetryRetentionJob(AgentDebugTraceService agentDebugTraceService,
                                 AgentUsageTelemetryService agentUsageTelemetryService) {
        this(agentDebugTraceService, agentUsageTelemetryService, Clock.systemUTC());
    }

    public TelemetryRetentionJob(AgentDebugTraceService agentDebugTraceService,
                                 AgentUsageTelemetryService agentUsageTelemetryService,
                                 Clock clock) {
        this.agentDebugTraceService = agentDebugTraceService;
        this.agentUsageTelemetryService = agentUsageTelemetryService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // Daily off-peak sweep; override via zipp.telemetry.cleanup-cron.
    @Scheduled(cron = "${zipp.telemetry.cleanup-cron:0 30 3 * * *}")
    public void purgeExpiredTelemetry() {
        purgeDebugTraceContent();
        purgeUsageTelemetry();
    }

    private void purgeDebugTraceContent() {
        if (agentDebugTraceService == null) {
            return;
        }
        try {
            int removed = agentDebugTraceService.cleanupExpiredContent();
            log.info("[telemetry-retention] debug trace content purged rows:{}", removed);
        } catch (Exception e) {
            log.warn("[telemetry-retention] debug trace content cleanup failed", e);
        }
    }

    private void purgeUsageTelemetry() {
        if (agentUsageTelemetryService == null) {
            return;
        }
        int days = Math.max(1, retentionDays);
        Instant cutoff = clock.instant().minus(Duration.ofDays(days));
        try {
            int removed = agentUsageTelemetryService.deleteTelemetryBefore(cutoff);
            log.info("[telemetry-retention] usage telemetry purged before:{} rows:{}", cutoff, removed);
        } catch (Exception e) {
            log.warn("[telemetry-retention] usage telemetry cleanup failed cutoff:{}", cutoff, e);
        }
    }
}
