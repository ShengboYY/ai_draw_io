package org.zipp.ai.application.turn;

public record TurnEngineAssignmentCommand(
        TurnKey key,
        String diagramId,
        VersionedRequestFingerprintSet fingerprints,
        ExecutionPolicySnapshot policy,
        MigrationStateSnapshot migration,
        MemoryWriteDeclaration memoryWrite
) {

    public TurnEngineAssignmentCommand {
        if (key == null || fingerprints == null || policy == null || migration == null || memoryWrite == null) {
            throw new IllegalArgumentException("assignment command values must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
    }
}
