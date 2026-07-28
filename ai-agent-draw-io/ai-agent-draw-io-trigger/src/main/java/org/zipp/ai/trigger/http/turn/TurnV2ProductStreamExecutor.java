package org.zipp.ai.trigger.http.turn;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Returns the HTTP emitter before waiting for a V2 attempt to reach its durable terminal state. */
@Component
public final class TurnV2ProductStreamExecutor implements AutoCloseable {

    private final ThreadPoolExecutor executor;

    public TurnV2ProductStreamExecutor(
            @Value("${turn-engine.http.product-v2-ingress.stream-threads:8}") int threads,
            @Value("${turn-engine.http.product-v2-ingress.stream-queue-capacity:100}")
            int queueCapacity
    ) {
        int size = Math.max(1, threads);
        int capacity = Math.max(1, queueCapacity);
        executor = new ThreadPoolExecutor(
                size,
                size,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "turn-v2-product-stream");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean execute(Runnable request) {
        Objects.requireNonNull(request, "request");
        try {
            executor.execute(request);
            return true;
        } catch (RejectedExecutionException capacityExhausted) {
            return false;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
