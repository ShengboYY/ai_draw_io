package org.zipp.ai.application.turn.context;

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
        if (summary.slice() != ContextSlice.SUMMARY
                || membership.slice() != ContextSlice.MEMBERSHIP
                || profile.slice() != ContextSlice.PROFILE
                || memory.slice() != ContextSlice.MEMORY) {
            throw new IllegalArgumentException("context read-set slices are out of order");
        }
        if (!hexDigest(digest)) {
            throw new IllegalArgumentException("context read-set digest must be SHA-256");
        }
        if (!digest.equals(ContextReadSetDigestCalculator.digestFor(
                schemaVersion, messageHighWater, summary, membership, profile, memory))) {
            throw new IllegalArgumentException("context read-set digest does not match its pins");
        }
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
                ContextReadSetDigestCalculator.digestFor(
                        schemaVersion, messageHighWater, summary, membership, profile, memory));
    }

    private static boolean hexDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
