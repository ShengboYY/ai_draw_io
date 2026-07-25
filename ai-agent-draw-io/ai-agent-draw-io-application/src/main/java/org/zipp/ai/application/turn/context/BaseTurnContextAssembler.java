package org.zipp.ai.application.turn.context;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.UserTurnCommand;

public interface BaseTurnContextAssembler {

    ContextAssemblyOutcome assemble(FencedAttempt attempt, UserTurnCommand command);
}
