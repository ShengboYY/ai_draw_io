package org.zipp.ai.application.memory;

public sealed interface MemoryManagementOutcome
        permits MemoryManagementOutcome.Updated, MemoryManagementOutcome.Gone,
        MemoryManagementOutcome.Rejected {
    record Updated(ConfirmedMemory memory) implements MemoryManagementOutcome {
    }

    record Gone(String code) implements MemoryManagementOutcome {
    }

    record Rejected(String code) implements MemoryManagementOutcome {
    }
}
