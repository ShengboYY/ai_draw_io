package org.zipp.ai.domain.agent.service.visualreview;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Keeps slow visual-review requests and provider calls off shared application executors. */
@Component
public class CanvasVisualReviewExecutor implements AutoCloseable {

    private final ThreadPoolExecutor requestExecutor;
    private final ThreadPoolExecutor providerExecutor;

    public CanvasVisualReviewExecutor(
            @Value("${zipp.visual-review.request-threads:4}") int requestThreads,
            @Value("${zipp.visual-review.request-queue-capacity:50}") int requestQueueCapacity,
            @Value("${zipp.visual-review.call-threads:2}") int callThreads,
            @Value("${zipp.visual-review.call-queue-capacity:20}") int callQueueCapacity) {
        requestExecutor = pool("visual-review-request", requestThreads, requestQueueCapacity);
        providerExecutor = pool("visual-review-provider", callThreads, callQueueCapacity);
    }

    public boolean executeRequest(Runnable request) {
        try {
            requestExecutor.execute(request);
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    public <T> T callProvider(Callable<T> call, long timeoutMillis)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<T> future = providerExecutor.submit(call);
        try {
            return future.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException error) {
            future.cancel(true);
            throw error;
        }
    }

    private ThreadPoolExecutor pool(String name, int threads, int queueCapacity) {
        int size = Math.max(1, threads);
        int capacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    @PreDestroy
    public void close() {
        requestExecutor.shutdownNow();
        providerExecutor.shutdownNow();
    }
}
