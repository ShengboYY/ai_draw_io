package org.zipp.ai.application.turn;

/** Immutable policy captured by assignment; live flags cannot rewrite an admitted turn. */
public record ExecutionPolicySnapshot(
        int schemaVersion,
        TurnEngineMode migrationMode,
        String snapshotJson,
        String policyHash
) {

    public ExecutionPolicySnapshot {
        if (schemaVersion <= 0 || migrationMode == null) {
            throw new IllegalArgumentException("invalid execution policy schema or mode");
        }
        ContractValues.requiredText(snapshotJson, "snapshotJson");
        ContractValues.requiredText(policyHash, "policyHash");
    }
}
