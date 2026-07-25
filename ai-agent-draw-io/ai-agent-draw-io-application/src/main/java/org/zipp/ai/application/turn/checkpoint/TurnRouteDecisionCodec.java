package org.zipp.ai.application.turn.checkpoint;

import org.zipp.ai.application.turn.planning.TurnRouteDecision;

/** Codec boundary for versioned, durable route decisions; implementations own canonical JSON. */
public interface TurnRouteDecisionCodec {

    EncodedTurnRouteDecision encode(TurnRouteDecision decision);

    TurnRouteDecision decode(TurnDecisionCheckpoint checkpoint);
}
