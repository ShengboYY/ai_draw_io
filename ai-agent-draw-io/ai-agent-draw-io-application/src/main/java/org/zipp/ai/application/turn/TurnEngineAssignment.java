package org.zipp.ai.application.turn;

public record TurnEngineAssignment(
        TurnKey key,
        String diagramId,
        VersionedRequestFingerprint fingerprint,
        SelectedTurnEngine selectedEngine,
        MigrationStateSnapshot migration,
        ExecutionPolicySnapshot policy,
        MemoryWriteDeclaration memoryWrite
) {

    public TurnEngineAssignment {
        if (key == null || fingerprint == null || selectedEngine == null
                || migration == null || policy == null || memoryWrite == null) {
            throw new IllegalArgumentException("assignment values must not be null");
        }
        ContractValues.requiredText(diagramId, "diagramId");
    }
}
