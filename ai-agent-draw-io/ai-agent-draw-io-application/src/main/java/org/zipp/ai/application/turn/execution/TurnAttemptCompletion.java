package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.PersistedTurnOutcome;
import org.zipp.ai.application.turn.TurnStatusRef;

/**
 * Attempt-level result consumed by either synchronous or streaming delivery.
 *
 * <p>Only {@link PersistedTerminal} is a product terminal. The other variants
 * deliberately carry a status reference or safe code without manufacturing a
 * durable terminal outcome.</p>
 */
public sealed interface TurnAttemptCompletion
        permits TurnAttemptCompletion.PersistedTerminal,
        TurnAttemptCompletion.AttemptOwnershipLost,
        TurnAttemptCompletion.AttemptSelfAborted,
        TurnAttemptCompletion.StatusOnly {

    record PersistedTerminal(PersistedTurnOutcome outcome) implements TurnAttemptCompletion {

        public PersistedTerminal {
            if (outcome == null) {
                throw new IllegalArgumentException("persisted terminal must not be null");
            }
        }
    }

    record AttemptOwnershipLost(TurnStatusRef status) implements TurnAttemptCompletion {

        public AttemptOwnershipLost {
            if (status == null) {
                throw new IllegalArgumentException("ownership-lost status must not be null");
            }
        }
    }

    record AttemptSelfAborted(TurnStatusRef status, String code) implements TurnAttemptCompletion {

        public AttemptSelfAborted {
            if (status == null) {
                throw new IllegalArgumentException("self-aborted status must not be null");
            }
            requiredText(code, "code");
        }

        private static void requiredText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    record StatusOnly(TurnStatusRef status, String code) implements TurnAttemptCompletion {

        public StatusOnly {
            if (status == null) {
                throw new IllegalArgumentException("status-only status must not be null");
            }
            requiredText(code, "code");
        }

        private static void requiredText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
