package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalJobExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Uses the application's bounded worker pool so Eval HTTP requests only enqueue work. */
@Component
public class ThreadPoolEvalJobExecutor implements IEvalJobExecutor {
    private final ThreadPoolExecutor executor;
    public ThreadPoolEvalJobExecutor(ThreadPoolExecutor executor) { this.executor = executor; }
    @Override public void execute(Runnable job) { executor.execute(job); }
}
