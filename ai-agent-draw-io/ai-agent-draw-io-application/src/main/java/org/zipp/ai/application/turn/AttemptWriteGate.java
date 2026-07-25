package org.zipp.ai.application.turn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * In-process write gate keyed by attempt id and epoch.
 *
 * <p>The database commit remains the authoritative fence. This gate closes
 * the local admission window first, preventing a lease-loss callback from
 * racing with a new strong commit.</p>
 */
public final class AttemptWriteGate implements TurnWriteGate {

    private final Map<AttemptIdentity, State> active = new HashMap<>();
    private final Set<AttemptIdentity> disabled = new HashSet<>();

    @Override
    public synchronized Optional<Permit> tryEnter(FencedAttempt attempt) {
        AttemptIdentity identity = identity(attempt);
        if (disabled.contains(identity)) {
            return Optional.empty();
        }
        State state = active.computeIfAbsent(identity, ignored -> new State());
        state.inFlight++;
        return Optional.of(new PermitImpl(this, identity));
    }

    @Override
    public synchronized void disableAndDrain(FencedAttempt attempt) {
        AttemptIdentity identity = identity(attempt);
        disabled.add(identity);
        State state = active.get(identity);
        if (state == null) {
            return;
        }

        boolean interrupted = false;
        while (state.inFlight > 0) {
            try {
                wait();
            } catch (InterruptedException ex) {
                // A lease-safety drain must finish before the attempt is detached.
                interrupted = true;
            }
        }
        active.remove(identity);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void leave(AttemptIdentity identity) {
        State state = Objects.requireNonNull(active.get(identity), "active attempt");
        state.inFlight--;
        notifyAll();
    }

    private static AttemptIdentity identity(FencedAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        return new AttemptIdentity(attempt.attemptId(), attempt.attemptEpoch());
    }

    private static final class State {

        private int inFlight;
    }

    private record AttemptIdentity(String attemptId, long epoch) {
    }

    private static final class PermitImpl implements Permit {

        private final AttemptWriteGate owner;
        private final AttemptIdentity identity;
        private boolean closed;

        private PermitImpl(AttemptWriteGate owner, AttemptIdentity identity) {
            this.owner = owner;
            this.identity = identity;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.leave(identity);
        }
    }
}
