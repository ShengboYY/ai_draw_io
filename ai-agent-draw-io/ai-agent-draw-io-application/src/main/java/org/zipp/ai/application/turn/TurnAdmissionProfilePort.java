package org.zipp.ai.application.turn;

/** Supplies the immutable fingerprint candidates and policy captured at first admission. */
public interface TurnAdmissionProfilePort {

    VersionedRequestFingerprintSet fingerprints(UserTurnCommand command);

    ExecutionPolicySnapshot policy(
            AuthenticatedActor actor,
            ConversationRef conversation,
            UserTurnCommand command
    );
}
