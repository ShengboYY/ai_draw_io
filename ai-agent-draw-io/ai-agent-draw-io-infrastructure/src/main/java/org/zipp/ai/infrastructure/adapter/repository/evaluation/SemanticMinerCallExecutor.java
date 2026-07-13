package org.zipp.ai.infrastructure.adapter.repository.evaluation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.evaluation.intake.ISemanticAnomalyMiner;
import org.zipp.ai.domain.agent.service.evaluation.intake.ISemanticMinerCallExecutor;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Dedicated bounded pool prevents slow Miner calls from consuming the Eval orchestration workers. */
@Component
public class SemanticMinerCallExecutor implements ISemanticMinerCallExecutor {
    private final ThreadPoolExecutor executor;

    public SemanticMinerCallExecutor(@Value("${zipp.evaluation.semantic-miner-call-threads:2}") int threads) {
        int size = Math.max(1, threads);
        this.executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100), runnable -> {
                    Thread thread = new Thread(runnable, "semantic-miner-call");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public ISemanticAnomalyMiner.Finding execute(ISemanticAnomalyMiner miner, String projection, Duration timeout) {
        Future<ISemanticAnomalyMiner.Finding> future = executor.submit(() -> miner.analyze(projection));
        try {
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("semantic miner call interrupted", e);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("semantic miner call timed out", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("semantic miner call failed", e.getCause());
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
