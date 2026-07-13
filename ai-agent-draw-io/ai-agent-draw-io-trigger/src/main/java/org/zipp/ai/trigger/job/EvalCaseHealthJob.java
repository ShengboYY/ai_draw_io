package org.zipp.ai.trigger.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.EvalCaseHealthOperationsService;

import java.util.Map;
import java.util.stream.Collectors;

/** Nightly maintenance only; health records contain no production identity or payload. */
@Slf4j @Component
@ConditionalOnProperty(name = "zipp.evaluation.case-health-job-enabled", havingValue = "true")
public class EvalCaseHealthJob {
    private final EvalCaseHealthOperationsService service;
    public EvalCaseHealthJob(EvalCaseHealthOperationsService service) { this.service = service; }
    @Scheduled(cron = "${zipp.evaluation.case-health-job-cron:0 30 2 * * *}")
    public void refresh() {
        try {
            Map<String, Long> counts = service.refresh(200).stream().collect(Collectors.groupingBy(
                    value -> value.getHealthStatus(), Collectors.counting()));
            log.info("[eval-case-health] statusCounts={}", counts);
            // Broken baselines and flaky cases are routed as non-sensitive operator alerts by log monitoring.
            if (counts.getOrDefault("BROKEN_BASELINE", 0L) > 0 || counts.getOrDefault("FLAKY", 0L) > 0) {
                log.warn("[eval-case-health-alert] brokenBaseline={} flaky={}",
                        counts.getOrDefault("BROKEN_BASELINE", 0L), counts.getOrDefault("FLAKY", 0L));
            }
        }
        catch (RuntimeException e) { log.warn("[eval-case-health] refresh failed errorClass={}", e.getClass().getSimpleName()); }
    }
}
