package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;

/**
 * Rebuilds context from the exact immutable versions in a pinned read-set. Implementations must
 * not replace a pin with a current-row lookup.
 */
public interface ContextReadSetMaterializerPort {

    ContextMaterializationOutcome materialize(
            FencedAttempt attempt,
            UserTurnCommand command,
            ContextReadSet readSet
    );
}
