package org.zipp.ai.application.turn.agent;

import org.zipp.ai.domain.retrieval.CancellationSignal;

/** One fresh, tool-free model decision over the current server-owned observation. */
@FunctionalInterface
public interface DiagramAgentDecisionPort {

    DiagramAgentAction decide(
            DiagramAgentObservation observation,
            CancellationSignal cancellation);
}
