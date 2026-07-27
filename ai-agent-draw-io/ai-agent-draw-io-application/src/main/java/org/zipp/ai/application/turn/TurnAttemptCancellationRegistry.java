package org.zipp.ai.application.turn;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Keeps the process-local cancellation target for each currently executing turn.
 *
 * <p>The durable cancellation CAS remains the authority. This registry only delivers the
 * already-persisted outcome to a local runner so it can stop its execution resource.</p>
 */
public final class TurnAttemptCancellationRegistry
        implements TurnAttemptCancellationSignalPort {

    private final ConcurrentMap<TurnKey, Registration> registrations = new ConcurrentHashMap<>();

    public Registration register(TurnKey key, Consumer<PersistedTurnOutcome> handler) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(handler, "handler");
        Registration registration = new Registration(key, handler);
        registrations.put(key, registration);
        return registration;
    }

    @Override
    public void signal(TurnKey key, PersistedTurnOutcome outcome) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(outcome, "outcome");
        Registration registration = registrations.get(key);
        if (registration != null) {
            registration.signal(outcome);
        }
    }

    public final class Registration implements AutoCloseable {

        private final TurnKey key;
        private final Consumer<PersistedTurnOutcome> handler;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Registration(TurnKey key, Consumer<PersistedTurnOutcome> handler) {
            this.key = key;
            this.handler = handler;
        }

        private void signal(PersistedTurnOutcome outcome) {
            if (!closed.get()) {
                handler.accept(outcome);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registrations.remove(key, this);
            }
        }
    }
}
