package org.zipp.ai.domain.retrieval.internal;

import org.zipp.ai.domain.retrieval.RequestProbe;
import org.zipp.ai.domain.retrieval.RequestProbeCommand;
import org.zipp.ai.domain.retrieval.RequestProbeService;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** Bounds the pre-router database probe so routing cannot inherit an unbounded dependency wait. */
public final class DeadlineRequestProbeService implements RequestProbeService {
    private final RequestProbeService delegate;
    private final ExecutorService executor;
    private final Duration timeout;

    public DeadlineRequestProbeService(RequestProbeService delegate, ExecutorService executor, Duration timeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override
    public RequestProbe probe(RequestProbeCommand command) {
        Future<RequestProbe> future = executor.submit(() -> delegate.probe(command));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeoutException) {
            future.cancel(true);
            throw new IllegalStateException("REQUEST_PROBE_TIMEOUT", timeoutException);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("REQUEST_PROBE_CANCELLED", interrupted);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("REQUEST_PROBE_FAILED", failed.getCause());
        }
    }
}
