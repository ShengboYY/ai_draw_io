package org.zipp.ai.domain.agent.service.evaluation.controlplane;

/** Async boundary so production HTTP does not execute a Dataset run inline and tests remain deterministic. */
public interface IEvalJobExecutor {
    void execute(Runnable job);
}
