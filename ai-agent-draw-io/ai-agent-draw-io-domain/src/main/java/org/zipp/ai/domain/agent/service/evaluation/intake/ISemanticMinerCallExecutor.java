package org.zipp.ai.domain.agent.service.evaluation.intake;

import java.time.Duration;

/** Bounded call boundary that enforces a hard deadline around the model adapter. */
public interface ISemanticMinerCallExecutor {
    ISemanticAnomalyMiner.Finding execute(ISemanticAnomalyMiner miner, String projection, Duration timeout);
}
