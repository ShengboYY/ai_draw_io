package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.evaluation.intake.ISemanticAnomalyMiner;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.SemanticMinerCallExecutor;

import java.time.Duration;

import static org.junit.Assert.assertThrows;

public class SemanticMinerCallExecutorTest {

    @Test
    public void shouldEnforceTheConfiguredModelDeadline() {
        SemanticMinerCallExecutor executor = new SemanticMinerCallExecutor(1);
        ISemanticAnomalyMiner slowMiner = new ISemanticAnomalyMiner() {
            @Override public Finding analyze(String projection) {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
            @Override public String version() { return "slow-v1"; }
        };
        try {
            assertThrows(IllegalStateException.class,
                    () -> executor.execute(slowMiner, "{}", Duration.ofMillis(20L)));
        } finally {
            executor.close();
        }
    }
}
