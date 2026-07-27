package org.zipp.ai.domain.agent.service.usage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Carries the submitting thread's run context onto a pooled task.
 *
 * <p>Modules that hand a model call to their own executor would otherwise run it on a thread with
 * no bound run, and every span or debug payload the call produces is dropped as unattributable.
 * The context is bound for exactly one task and cleared afterwards, so a shared pool never leaks a
 * run into an unrelated task.</p>
 */
public final class TelemetryPropagatingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    private TelemetryPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Returns the pool unchanged when it already propagates, so wrapping stays idempotent. */
    public static ExecutorService wrap(ExecutorService delegate) {
        return delegate instanceof TelemetryPropagatingExecutorService
                ? delegate
                : new TelemetryPropagatingExecutorService(delegate);
    }

    private Runnable carry(Runnable task) {
        Objects.requireNonNull(task, "task");
        AgentUsageTelemetryContext.RunContext captured =
                AgentUsageTelemetryContext.current().orElse(null);
        if (captured == null) {
            return task;
        }
        return () -> {
            try (AgentUsageTelemetryContext.Scope ignored =
                         AgentUsageTelemetryContext.bind(captured)) {
                task.run();
            }
        };
    }

    private <T> Callable<T> carry(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        AgentUsageTelemetryContext.RunContext captured =
                AgentUsageTelemetryContext.current().orElse(null);
        if (captured == null) {
            return task;
        }
        return () -> {
            try (AgentUsageTelemetryContext.Scope ignored =
                         AgentUsageTelemetryContext.bind(captured)) {
                return task.call();
            }
        };
    }

    private <T> Collection<Callable<T>> carryAll(Collection<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        List<Callable<T>> carried = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            carried.add(carry(task));
        }
        return carried;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(carry(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(carry(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(carry(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(carry(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(carryAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(carryAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(carryAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(carryAll(tasks), timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
