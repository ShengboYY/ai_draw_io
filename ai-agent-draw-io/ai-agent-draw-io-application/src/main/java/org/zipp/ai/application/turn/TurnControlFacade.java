package org.zipp.ai.application.turn;

public interface TurnControlFacade {

    TurnStatusQueryOutcome status(AuthenticatedActor actor, TurnStatusQuery query);

    CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command);

    TurnAttemptLeasePort.HeartbeatOutcome heartbeat(FencedAttempt attempt);

    DeadlineCancelOutcome cancelAtDeadline(FencedAttempt attempt, AttemptDeadlineReason reason);

    TurnAttemptTakeoverPort.TakeoverOutcome takeover(AuthenticatedActor actor, TurnKey key);
}
