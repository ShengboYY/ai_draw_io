package org.zipp.ai.application.memory;

/** Owner-fenced management result with stable error codes for HTTP adapters. */
public sealed interface AutoMemoryManagementOutcome
        permits AutoMemoryManagementOutcome.Updated, AutoMemoryManagementOutcome.Deleted,
        AutoMemoryManagementOutcome.Gone, AutoMemoryManagementOutcome.Rejected {

    record Updated(AutoMemory memory) implements AutoMemoryManagementOutcome {
        public Updated {
            if (memory == null) {
                throw new IllegalArgumentException("memory must not be null");
            }
        }
    }

    record Deleted(String memoryId) implements AutoMemoryManagementOutcome {
        public Deleted {
            if (memoryId == null || memoryId.isBlank()) {
                throw new IllegalArgumentException("memoryId must not be blank");
            }
        }
    }

    record Gone(String code) implements AutoMemoryManagementOutcome {
    }

    record Rejected(String code) implements AutoMemoryManagementOutcome {
    }
}
