package org.zipp.ai.domain.agent.service.evaluation.visual;

import java.time.Duration;

/** Bounded model-call boundary for administrator-triggered production image analysis. */
public interface IVisualMinerCallExecutor {
    IVisualAnomalyMiner.Finding execute(IVisualAnomalyMiner miner, IVisualAnomalyMiner.Input input, Duration timeout);
}
