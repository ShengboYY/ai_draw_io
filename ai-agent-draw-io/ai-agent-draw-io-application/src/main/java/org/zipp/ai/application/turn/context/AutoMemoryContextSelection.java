package org.zipp.ai.application.turn.context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact bounded Memory projection selected and pinned for one Turn. */
public record AutoMemoryContextSelection(
        AutoMemoryContext context,
        List<Reference> references,
        long version,
        String digest
) {
    public AutoMemoryContextSelection {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        references = List.copyOf(references == null ? List.of() : references);
        if (references.size() != context.entries().size() || references.size() > 16) {
            throw new IllegalArgumentException("Memory context references are inconsistent");
        }
        Set<String> ids = new HashSet<>();
        if (references.stream().anyMatch(reference -> !ids.add(reference.memoryId()))) {
            throw new IllegalArgumentException("Memory context references must be unique");
        }
        if ((references.isEmpty() && version != 0)
                || (!references.isEmpty() && version < 1)) {
            throw new IllegalArgumentException("Memory context version is inconsistent");
        }
        if (!hexDigest(digest)) {
            throw new IllegalArgumentException("Memory context digest must be SHA-256");
        }
    }

    public boolean empty() {
        return references.isEmpty();
    }

    /** Source-free identity retained in the durable Turn read-set. */
    public record Reference(String memoryId, long version) {
        public Reference {
            if (memoryId == null || memoryId.isBlank() || memoryId.length() > 64 || version < 1) {
                throw new IllegalArgumentException("invalid pinned Memory reference");
            }
            memoryId = memoryId.trim();
        }
    }

    private static boolean hexDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
