package org.zipp.ai.application.turn;

/** Builds the canonical assignment command before Router/LLM or source inspection. */
public final class TurnEngineAdmissionService {

    private final TurnEngineMigrationStatePort migrationState;
    private final TurnEngineAssignmentPort assignments;

    public TurnEngineAdmissionService(
            TurnEngineMigrationStatePort migrationState,
            TurnEngineAssignmentPort assignments
    ) {
        this.migrationState = migrationState;
        this.assignments = assignments;
    }

    public AdmissionWriteOutcome admit(
            AuthenticatedActor actor,
            ConversationRef conversation,
            UserTurnCommand command,
            VersionedRequestFingerprintSet fingerprints,
            ExecutionPolicySnapshot policy
    ) {
        if (actor == null || conversation == null || command == null || fingerprints == null || policy == null) {
            throw new IllegalArgumentException("admission values must not be null");
        }
        if (!conversation.isActive()) {
            return new AdmissionWriteOutcome.Rejected(
                    TurnKey.of(actor, conversation, command.turnId()), "CONVERSATION_NOT_ACTIVE");
        }
        TurnKey key = TurnKey.of(actor, conversation, command.turnId());
        MigrationStateSnapshot migration = migrationState.current();
        return assignments.assignOrReuse(new TurnEngineAssignmentCommand(
                key,
                conversation.diagramId(),
                fingerprints,
                policy,
                migration,
                command.declarations().memoryWrite()
        ));
    }
}
