package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnWriteGate;

import java.util.Objects;

/**
 * Guards an attempt-scoped deadline callback with the same local write gate used by commits.
 *
 * <p>The permit remains held across the durable cancellation CAS. A lease-safety drain can
 * therefore either wait for this callback to finish or close the gate before it starts; it can
 * never race a new deadline write after the attempt has been disabled.</p>
 */
public final class TurnAttemptDeadlineSupervisor {

    private final AttemptDeadlineCancellationPort deadlines;
    private final TurnWriteGate writeGate;

    public TurnAttemptDeadlineSupervisor(
            AttemptDeadlineCancellationPort deadlines,
            TurnWriteGate writeGate
    ) {
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.writeGate = Objects.requireNonNull(writeGate, "writeGate");
    }

    public TurnAttemptDeadlineOutcome cancel(
            FencedAttempt attempt,
            AttemptDeadlineReason reason
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(reason, "reason");
        var permit = writeGate.tryEnter(attempt);
        if (permit.isEmpty()) {
            return new TurnAttemptDeadlineOutcome.WriteGateDisabled();
        }
        try (TurnWriteGate.Permit ignored = permit.get()) {
            return new TurnAttemptDeadlineOutcome.Delegated(deadlines.cancel(attempt, reason));
        }
    }
}
