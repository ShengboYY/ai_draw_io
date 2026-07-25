package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;

public interface ContextReadSetQueryPort {

    ContextReadSetLoadOutcome loadPinned(FencedAttempt attempt);
}
