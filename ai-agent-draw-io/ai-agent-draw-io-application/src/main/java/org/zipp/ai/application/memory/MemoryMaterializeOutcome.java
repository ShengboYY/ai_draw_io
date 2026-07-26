package org.zipp.ai.application.memory;

public sealed interface MemoryMaterializeOutcome
        permits MemoryMaterializeOutcome.Materialized, MemoryMaterializeOutcome.AlreadyMaterialized,
        MemoryMaterializeOutcome.Gone, MemoryMaterializeOutcome.Rejected {
    record Materialized(ConfirmedMemory memory) implements MemoryMaterializeOutcome {
    }

    record AlreadyMaterialized(ConfirmedMemory memory) implements MemoryMaterializeOutcome {
    }

    record Gone(String code) implements MemoryMaterializeOutcome {
    }

    record Rejected(String code) implements MemoryMaterializeOutcome {
    }
}
