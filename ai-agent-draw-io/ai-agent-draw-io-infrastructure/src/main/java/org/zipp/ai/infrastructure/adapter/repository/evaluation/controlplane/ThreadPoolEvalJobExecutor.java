package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalJobExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Uses the application's bounded worker pool so Eval HTTP requests only enqueue work. */
@Component
@ConditionalOnBean(name = "threadPoolExecutor")
public class ThreadPoolEvalJobExecutor implements IEvalJobExecutor {
    private final ThreadPoolExecutor executor;

    public ThreadPoolEvalJobExecutor(
            @Qualifier("threadPoolExecutor") ThreadPoolExecutor executor) {
        // Eval jobs stay on the bounded application pool when optional subsystem pools are enabled.
        this.executor = executor;
    }

    @Override public void execute(Runnable job) { executor.execute(job); }
}
