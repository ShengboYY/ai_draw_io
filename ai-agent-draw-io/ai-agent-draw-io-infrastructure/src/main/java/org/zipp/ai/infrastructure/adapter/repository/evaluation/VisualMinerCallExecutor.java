package org.zipp.ai.infrastructure.adapter.repository.evaluation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.visual.IVisualMinerCallExecutor;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Separate bounded pool ensures a slow VLM cannot consume production Agent or Eval workers. */
@Component
public class VisualMinerCallExecutor implements IVisualMinerCallExecutor {
    private final ThreadPoolExecutor executor;
    public VisualMinerCallExecutor(@Value("${zipp.evaluation.visual-miner-call-threads:1}") int threads) {
        int size = Math.max(1, threads);
        executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(20), runnable -> {
            Thread thread = new Thread(runnable, "visual-miner-call"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    @Override public IVisualAnomalyMiner.Finding execute(IVisualAnomalyMiner miner, IVisualAnomalyMiner.Input input, Duration timeout) {
        Future<IVisualAnomalyMiner.Finding> future = executor.submit(() -> miner.analyze(input));
        try { return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); future.cancel(true); throw new IllegalStateException("visual miner interrupted", e); }
        catch (java.util.concurrent.TimeoutException e) { future.cancel(true); throw new IllegalStateException("visual miner timed out", e); }
        catch (java.util.concurrent.ExecutionException e) { throw new IllegalStateException("visual miner failed", e.getCause()); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
