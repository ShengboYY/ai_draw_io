package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.LeaseTimingAnchor;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnSubmission;
import org.zipp.ai.application.turn.UserTurnCommand;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Owns the process-local lifecycle after a V2 claim without changing production transport.
 *
 * <p>The caller owns both executors. Heartbeats renew the same attempt id and epoch, while a
 * lease-loss outcome closes the executor's write gate before this runner completes ownership
 * loss. Delivery detachment only swaps the event sink to a no-op.</p>
 */
public final class TurnAttemptExecutionRunner {

    private static final Duration HEARTBEAT_FAILURE_RETRY = Duration.ofSeconds(1);

    private final TurnV2TurnExecutor executor;
    private final TurnAttemptLeaseSupervisor heartbeat;
    private final Executor executionExecutor;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier monotonicNanos;

    public TurnAttemptExecutionRunner(
            TurnV2TurnExecutor executor,
            TurnAttemptLeaseSupervisor heartbeat,
            Executor executionExecutor,
            ScheduledExecutorService scheduler
    ) {
        this(executor, heartbeat, executionExecutor, scheduler, System::nanoTime);
    }

    TurnAttemptExecutionRunner(
            TurnV2TurnExecutor executor,
            TurnAttemptLeaseSupervisor heartbeat,
            Executor executionExecutor,
            ScheduledExecutorService scheduler,
            LongSupplier monotonicNanos
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.executionExecutor = Objects.requireNonNull(executionExecutor, "executionExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    public TurnHandle start(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        return start(accepted, command, events, null, null);
    }

    /** Starts execution with an optional attempt-scoped server deadline. */
    public TurnHandle start(
            TurnSubmission.ExecutionAccepted accepted,
            UserTurnCommand command,
            TurnEventSink events,
            Duration executionDeadline,
            TurnAttemptDeadlineSupervisor deadlines
    ) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");
        if ((executionDeadline == null) != (deadlines == null)
                || (executionDeadline != null && executionDeadline.isNegative())) {
            throw new IllegalArgumentException("deadline and deadline supervisor must agree");
        }

        AttemptState state = new AttemptState(
                accepted, command, new DetachableEventSink(events), deadlines);
        if (!state.scheduleHeartbeat(initialDelay(accepted.leaseTiming()))) {
            state.complete(new TurnAttemptCompletion.AttemptSelfAborted(
                    new TurnStatusRef(accepted.key()), "TURN_HEARTBEAT_SCHEDULER_FAILED"));
            return state;
        }
        if (executionDeadline != null
                && !state.scheduleDeadline(initialDeadlineDelay(accepted.leaseTiming(), executionDeadline))) {
            state.complete(new TurnAttemptCompletion.AttemptSelfAborted(
                    new TurnStatusRef(accepted.key()), "TURN_DEADLINE_SCHEDULER_FAILED"));
            return state;
        }
        try {
            executionExecutor.execute(state::execute);
        } catch (RuntimeException exception) {
            state.complete(new TurnAttemptCompletion.AttemptSelfAborted(
                    new TurnStatusRef(accepted.key()), "TURN_EXECUTION_DISPATCH_FAILED"));
        }
        return state;
    }

    private Duration initialDelay(LeaseTimingAnchor timing) {
        return delayUntilDue(timing);
    }

    private Duration delayUntilDue(LeaseTimingAnchor timing) {
        long elapsed = Math.max(0L, monotonicNanos.getAsLong() - timing.callStartedNanos());
        long renewNanos = timing.lease().renewWithin().toNanos();
        return Duration.ofNanos(Math.max(0L, renewNanos - elapsed));
    }

    private Duration initialDeadlineDelay(LeaseTimingAnchor timing, Duration deadline) {
        long elapsed = Math.max(0L, monotonicNanos.getAsLong() - timing.callStartedNanos());
        return Duration.ofNanos(Math.max(0L, deadline.toNanos() - elapsed));
    }

    private final class AttemptState implements TurnHandle {

        private final TurnSubmission.ExecutionAccepted accepted;
        private final UserTurnCommand command;
        private final DetachableEventSink events;
        private final TurnAttemptDeadlineSupervisor deadlines;
        private final CompletableFuture<TurnAttemptCompletion> completion = new CompletableFuture<>();
        private final Object lock = new Object();
        private FencedAttempt currentAttempt;
        private LeaseTimingAnchor timing;
        private ScheduledFuture<?> heartbeatFuture;
        private ScheduledFuture<?> deadlineFuture;
        private boolean completed;

        private AttemptState(
                TurnSubmission.ExecutionAccepted accepted,
                UserTurnCommand command,
                DetachableEventSink events,
                TurnAttemptDeadlineSupervisor deadlines
        ) {
            this.accepted = accepted;
            this.command = command;
            this.events = events;
            this.deadlines = deadlines;
            this.currentAttempt = accepted.attempt();
            this.timing = accepted.leaseTiming();
        }

        @Override
        public FencedAttempt attempt() {
            synchronized (lock) {
                return currentAttempt;
            }
        }

        @Override
        public CompletionStage<TurnAttemptCompletion> completion() {
            return completion;
        }

        @Override
        public void detach() {
            events.detach();
        }

        private boolean scheduleHeartbeat(Duration delay) {
            synchronized (lock) {
                if (completed) {
                    return true;
                }
                try {
                    heartbeatFuture = scheduler.schedule(
                            this::heartbeatTick, delay.toNanos(), TimeUnit.NANOSECONDS);
                    return true;
                } catch (RuntimeException exception) {
                    return false;
                }
            }
        }

        private boolean scheduleDeadline(Duration delay) {
            synchronized (lock) {
                if (completed) {
                    return true;
                }
                try {
                    deadlineFuture = scheduler.schedule(
                            this::deadlineTick, delay.toNanos(), TimeUnit.NANOSECONDS);
                    return true;
                } catch (RuntimeException exception) {
                    return false;
                }
            }
        }

        private void deadlineTick() {
            FencedAttempt attempt;
            synchronized (lock) {
                if (completed) {
                    return;
                }
                attempt = currentAttempt;
            }
            TurnAttemptDeadlineOutcome outcome;
            try {
                outcome = deadlines.cancel(attempt, AttemptDeadlineReason.EXECUTION_DEADLINE);
            } catch (RuntimeException exception) {
                complete(new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "TURN_DEADLINE_FAILED"));
                return;
            }
            if (outcome instanceof TurnAttemptDeadlineOutcome.WriteGateDisabled) {
                complete(new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "TURN_WRITE_GATE_DISABLED"));
                return;
            }
            DeadlineCancelOutcome delegated =
                    ((TurnAttemptDeadlineOutcome.Delegated) outcome).outcome();
            if (delegated instanceof DeadlineCancelOutcome.Cancelled cancelled) {
                complete(new TurnAttemptCompletion.PersistedTerminal(cancelled.outcome()));
                return;
            }
            if (delegated instanceof DeadlineCancelOutcome.AlreadyTerminal terminal) {
                complete(new TurnAttemptCompletion.PersistedTerminal(terminal.outcome()));
                return;
            }
            if (delegated instanceof DeadlineCancelOutcome.FenceLost lost) {
                complete(new TurnAttemptCompletion.AttemptOwnershipLost(
                        new TurnStatusRef(lost.status().key())));
                return;
            }
            if (delegated instanceof DeadlineCancelOutcome.TerminalUnavailable unavailable) {
                complete(new TurnAttemptCompletion.StatusOnly(
                        new TurnStatusRef(unavailable.status().key()), unavailable.code()));
                return;
            }
            DeadlineCancelOutcome.TransientFailure transientFailure =
                    (DeadlineCancelOutcome.TransientFailure) delegated;
            complete(new TurnAttemptCompletion.AttemptSelfAborted(
                    new TurnStatusRef(accepted.key()), transientFailure.code()));
        }

        private void heartbeatTick() {
            FencedAttempt attempt;
            LeaseTimingAnchor currentTiming;
            synchronized (lock) {
                if (completed) {
                    return;
                }
                attempt = currentAttempt;
                currentTiming = timing;
            }
            if (!heartbeat.isDue(currentTiming, monotonicNanos.getAsLong())) {
                rescheduleHeartbeat(delayUntilDue(currentTiming));
                return;
            }

            TurnAttemptHeartbeatOutcome outcome;
            try {
                outcome = heartbeat.heartbeat(attempt);
            } catch (RuntimeException exception) {
                rescheduleHeartbeat(HEARTBEAT_FAILURE_RETRY);
                return;
            }
            if (outcome instanceof TurnAttemptHeartbeatOutcome.Renewed renewed) {
                synchronized (lock) {
                    if (completed) {
                        return;
                    }
                    currentAttempt = renewed.attempt();
                    timing = renewed.timing();
                }
                rescheduleHeartbeat(delayUntilDue(renewed.timing()));
                return;
            }
            if (outcome instanceof TurnAttemptHeartbeatOutcome.Retry retry) {
                rescheduleHeartbeat(retry.retryAfter());
                return;
            }
            if (outcome instanceof TurnAttemptHeartbeatOutcome.OwnershipLost lost) {
                complete(new TurnAttemptCompletion.AttemptOwnershipLost(lost.status()));
                return;
            }
            if (outcome instanceof TurnAttemptHeartbeatOutcome.PersistedTerminal terminal) {
                complete(new TurnAttemptCompletion.PersistedTerminal(terminal.outcome()));
                return;
            }
            TurnAttemptHeartbeatOutcome.Unavailable unavailable =
                    (TurnAttemptHeartbeatOutcome.Unavailable) outcome;
            complete(new TurnAttemptCompletion.StatusOnly(
                    unavailable.status(), unavailable.code()));
        }

        private void rescheduleHeartbeat(Duration delay) {
            if (!scheduleHeartbeat(delay)) {
                complete(new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "TURN_HEARTBEAT_SCHEDULER_FAILED"));
            }
        }

        private void execute() {
            TurnAttemptCompletion result;
            try {
                result = executor.execute(accepted, command, events);
            } catch (RuntimeException exception) {
                result = new TurnAttemptCompletion.AttemptSelfAborted(
                        new TurnStatusRef(accepted.key()), "TURN_EXECUTION_FAILED");
            }
            complete(result);
        }

        private void complete(TurnAttemptCompletion result) {
            Objects.requireNonNull(result, "result");
            ScheduledFuture<?> scheduled;
            synchronized (lock) {
                if (completed) {
                    return;
                }
                completed = true;
                scheduled = heartbeatFuture;
                ScheduledFuture<?> deadline = deadlineFuture;
                if (deadline != null) {
                    deadline.cancel(false);
                }
            }
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            completion.complete(result);
        }
    }

    private static final class DetachableEventSink implements TurnEventSink {

        private static final TurnEventSink DETACHED = ignored -> { };

        private final AtomicReference<TurnEventSink> delegate;

        private DetachableEventSink(TurnEventSink delegate) {
            this.delegate = new AtomicReference<>(delegate);
        }

        @Override
        public void publish(org.zipp.ai.application.turn.TurnEvent event) {
            TurnEventSink current = delegate.get();
            if (current == DETACHED) {
                return;
            }
            try {
                current.publish(event);
            } catch (RuntimeException ignored) {
                // Delivery failure detaches this subscriber; it never cancels the product turn.
                detach();
            }
        }

        private void detach() {
            delegate.set(DETACHED);
        }
    }
}
