package org.zipp.ai.application.turn.context;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, source-free context version pin. The digest covers all pins and the conversation
 * high-water mark so takeover cannot silently read newer context.
 */
public record ContextReadSet(
        int schemaVersion,
        long messageHighWater,
        ContextSlicePin summary,
        ContextSlicePin membership,
        ContextSlicePin profile,
        ContextSlicePin memory,
        List<AutoMemoryContextSelection.Reference> memorySelection,
        String digest
) {

    public ContextReadSet {
        if (schemaVersion <= 0 || messageHighWater < 0) {
            throw new IllegalArgumentException("invalid context read-set version or high-water");
        }
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(memory, "memory");
        memorySelection = List.copyOf(memorySelection == null ? List.of() : memorySelection);
        if (summary.slice() != ContextSlice.SUMMARY
                || membership.slice() != ContextSlice.MEMBERSHIP
                || profile.slice() != ContextSlice.PROFILE
                || memory.slice() != ContextSlice.MEMORY) {
            throw new IllegalArgumentException("context read-set slices are out of order");
        }
        if (schemaVersion >= 2
                && (memory.state() == ContextPinState.PINNED) != !memorySelection.isEmpty()) {
            throw new IllegalArgumentException("Memory pin and selection are inconsistent");
        }
        if (schemaVersion < 2 && !memorySelection.isEmpty()) {
            throw new IllegalArgumentException("Memory selection requires read-set schema v2");
        }
        if (!hexDigest(digest)) {
            throw new IllegalArgumentException("context read-set digest must be SHA-256");
        }
        if (!digest.equals(ContextReadSetDigestCalculator.digestFor(
                schemaVersion, messageHighWater, summary, membership, profile, memory,
                memorySelection))) {
            throw new IllegalArgumentException("context read-set digest does not match its pins");
        }
    }

    /** Compatibility constructor for schema-v1 read-sets that predate pinned Memory identities. */
    public ContextReadSet(
            int schemaVersion,
            long messageHighWater,
            ContextSlicePin summary,
            ContextSlicePin membership,
            ContextSlicePin profile,
            ContextSlicePin memory,
            String digest
    ) {
        this(schemaVersion, messageHighWater, summary, membership, profile, memory, List.of(), digest);
    }

    public static ContextReadSet create(
            int schemaVersion,
            long messageHighWater,
            ContextSlicePin summary,
            ContextSlicePin membership,
            ContextSlicePin profile,
            ContextSlicePin memory
    ) {
        return new ContextReadSet(
                schemaVersion,
                messageHighWater,
                summary,
                membership,
                profile,
                memory,
                List.of(),
                ContextReadSetDigestCalculator.digestFor(
                        schemaVersion, messageHighWater, summary, membership, profile, memory,
                        List.of()));
    }

    public static ContextReadSet createWithMemorySelection(
            long messageHighWater,
            ContextSlicePin summary,
            ContextSlicePin membership,
            ContextSlicePin profile,
            ContextSlicePin memory,
            List<AutoMemoryContextSelection.Reference> memorySelection
    ) {
        int schemaVersion = 2;
        List<AutoMemoryContextSelection.Reference> references = List.copyOf(memorySelection);
        return new ContextReadSet(
                schemaVersion,
                messageHighWater,
                summary,
                membership,
                profile,
                memory,
                references,
                ContextReadSetDigestCalculator.digestFor(
                        schemaVersion, messageHighWater, summary, membership, profile, memory,
                        references));
    }

    private static boolean hexDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
