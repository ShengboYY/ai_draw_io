package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualAnomalyMiner;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.VisualMinerCallExecutor;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertThrows;

public class VisualMinerCallExecutorTest {
    @Test
    public void enforcesHardProviderDeadline() {
        VisualMinerCallExecutor calls = new VisualMinerCallExecutor(1);
        IVisualAnomalyMiner slow = new IVisualAnomalyMiner() {
            @Override public Finding analyze(Input input) { try { Thread.sleep(5_000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return new Finding(false, 0D, "NONE", List.of(), "low", "none", false); }
            @Override public String version() { return "slow-v1"; }
        };
        try {
            assertThrows(IllegalStateException.class, () -> calls.execute(slow, new IVisualAnomalyMiner.Input(null, List.of(), "flowchart"), Duration.ofMillis(20)));
        } finally { calls.close(); }
    }
}
