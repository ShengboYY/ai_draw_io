package org.zipp.ai.application.turn;

import java.util.Objects;

/** Builds the canonical assignment command before Router/LLM or source inspection. */
public final class TurnEngineAdmissionService {

    private final TurnEngineMigrationStatePort migrationState;
    private final TurnEngineAssignmentPort assignments;
    private final AdmissionBarrier admissionBarrier;
    private final TurnEngineCohortSelector cohortSelector;

    public TurnEngineAdmissionService(
            TurnEngineMigrationStatePort migrationState,
            TurnEngineAssignmentPort assignments,
            AdmissionBarrier admissionBarrier
    ) {
        this(migrationState, assignments, admissionBarrier,
                StableTurnEngineCohortSelector.legacyOnly());
    }

    public TurnEngineAdmissionService(
            TurnEngineMigrationStatePort migrationState,
            TurnEngineAssignmentPort assignments,
            AdmissionBarrier admissionBarrier,
            TurnEngineCohortSelector cohortSelector
    ) {
        this.migrationState = Objects.requireNonNull(migrationState, "migrationState");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.admissionBarrier = Objects.requireNonNull(admissionBarrier, "admissionBarrier");
        this.cohortSelector = Objects.requireNonNull(cohortSelector, "cohortSelector");
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
        TurnKey key = TurnKey.of(actor, conversation, command.turnId());
        if (!admissionBarrier.tryEnter()) {
            return new AdmissionWriteOutcome.Rejected(key, "TURN_INSTANCE_NOT_READY");
        }
        try {
            return admitAfterEntry(actor, conversation, command, fingerprints, policy);
        } finally {
            admissionBarrier.leave();
        }
    }

    /**
     * Runs the durable admission steps for a caller that already owns the local drain scope.
     * The facade enters before conversation resolution, so a concurrent pause must not reject
     * an admission that has already crossed the barrier.
     */
    AdmissionWriteOutcome admitAfterEntry(
            AuthenticatedActor actor,
            ConversationRef conversation,
            UserTurnCommand command,
            VersionedRequestFingerprintSet fingerprints,
            ExecutionPolicySnapshot policy
    ) {
        if (actor == null || conversation == null || command == null || fingerprints == null || policy == null) {
            throw new IllegalArgumentException("admission values must not be null");
        }
        TurnKey key = TurnKey.of(actor, conversation, command.turnId());
        if (!conversation.isActive()) {
            return new AdmissionWriteOutcome.Rejected(key, "CONVERSATION_NOT_ACTIVE");
        }
        MigrationStateSnapshot migration = migrationState.current();
        SelectedTurnEngine selectedEngine = selectEngine(actor, migration.mode());
        if (selectedEngine == null) {
            return new AdmissionWriteOutcome.Rejected(key, "TURN_ENGINE_RETIRED");
        }
        return assignments.assignOrReuse(new TurnEngineAssignmentCommand(
                key,
                conversation.diagramId(),
                fingerprints,
                policy,
                migration,
                command.declarations().memoryWrite(),
                selectedEngine
        ));
    }

    private SelectedTurnEngine selectEngine(AuthenticatedActor actor, TurnEngineMode mode) {
        return switch (mode) {
            case LEGACY -> SelectedTurnEngine.LEGACY;
            case V2_CANARY -> cohortSelector.select(actor.cohortKey());
            case ALL_V2 -> SelectedTurnEngine.V2;
            case RETIRED -> null;
        };
    }
}
