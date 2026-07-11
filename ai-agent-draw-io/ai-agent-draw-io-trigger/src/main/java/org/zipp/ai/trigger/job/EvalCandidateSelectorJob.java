package org.zipp.ai.trigger.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.evaluation.intake.DeterministicCandidateSelectorService;

/** Periodically discovers metadata-only Eval Candidates outside the user request path. */
@Slf4j
@Component
@ConditionalOnProperty(name = "zipp.evaluation.candidate-selector-enabled", havingValue = "true")
public class EvalCandidateSelectorJob {
    private final DeterministicCandidateSelectorService selector;

    public EvalCandidateSelectorJob(DeterministicCandidateSelectorService selector) {
        this.selector = selector;
    }

    @Scheduled(fixedDelayString = "${zipp.evaluation.candidate-selector-delay-ms:300000}")
    public void discoverCandidates() {
        try {
            int count = selector.discover(200).size();
            log.info("[eval-candidate-selector] selected candidates:{}", count);
        } catch (Exception e) {
            // A selector outage must not affect production Agent execution.
            log.warn("[eval-candidate-selector] discovery failed", e);
        }
    }
}
