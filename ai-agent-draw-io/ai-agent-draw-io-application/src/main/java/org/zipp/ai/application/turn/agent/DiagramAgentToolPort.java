package org.zipp.ai.application.turn.agent;

import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.PlainDrawPlan;

/** Executes one authorized draft action without access to the committed canvas repository. */
@FunctionalInterface
public interface DiagramAgentToolPort {

    DiagramAgentToolResult execute(
            FencedAttempt attempt,
            PlainDrawPlan plan,
            DiagramAgentToolRequest request);
}
