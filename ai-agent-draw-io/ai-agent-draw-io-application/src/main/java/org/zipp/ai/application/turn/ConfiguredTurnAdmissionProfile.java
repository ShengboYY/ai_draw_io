package org.zipp.ai.application.turn;

import java.util.List;
import java.util.Objects;

/** Captures the deterministic M1 fingerprint and policy snapshot before execution begins. */
public final class ConfiguredTurnAdmissionProfile implements TurnAdmissionProfilePort {

    private final TurnEngineMigrationStatePort migrationState;
    private final int policySchemaVersion;
    private final String policySnapshotJson;
    private final String policyHash;

    public ConfiguredTurnAdmissionProfile(
            TurnEngineMigrationStatePort migrationState,
            int policySchemaVersion,
            String policySnapshotJson,
            String policyHash
    ) {
        this.migrationState = Objects.requireNonNull(migrationState, "migrationState");
        if (policySchemaVersion <= 0) {
            throw new IllegalArgumentException("policySchemaVersion must be positive");
        }
        this.policySchemaVersion = policySchemaVersion;
        this.policySnapshotJson = ContractValues.requiredText(policySnapshotJson, "policySnapshotJson");
        this.policyHash = ContractValues.requiredText(policyHash, "policyHash");
    }

    @Override
    public VersionedRequestFingerprintSet fingerprints(UserTurnCommand command) {
        return new VersionedRequestFingerprintSet(List.of(TurnRequestFingerprintCalculator.current(command)));
    }

    @Override
    public ExecutionPolicySnapshot policy(
            AuthenticatedActor actor,
            ConversationRef conversation,
            UserTurnCommand command
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(command, "command");
        return new ExecutionPolicySnapshot(
                policySchemaVersion,
                migrationState.current().mode(),
                policySnapshotJson,
                policyHash);
    }
}
