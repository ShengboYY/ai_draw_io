package org.zipp.ai.application.turn;

import java.util.Optional;

/**
 * Local write admission for one fenced attempt.
 *
 * <p>A lease-safety interruption closes only the affected attempt. Callers must
 * drain before detaching the attempt so no new application write can start
 * after ownership has been lost.</p>
 */
public interface TurnWriteGate {

    Optional<Permit> tryEnter(FencedAttempt attempt);

    void disableAndDrain(FencedAttempt attempt);

    interface Permit extends AutoCloseable {

        @Override
        void close();
    }
}
