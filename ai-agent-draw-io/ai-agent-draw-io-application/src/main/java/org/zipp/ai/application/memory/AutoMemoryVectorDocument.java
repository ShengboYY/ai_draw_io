package org.zipp.ai.application.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Transient, content-bearing source for one rebuildable Memory vector. */
public record AutoMemoryVectorDocument(
        String vectorId,
        String memoryId,
        AutoMemoryScope scope,
        CandidateKind kind,
        CandidateState state,
        String title,
        String canonicalText,
        long projectionRevision
) {
    private static final String CURRENT_PREFIX = "am-current.";
    private static final String CHALLENGER_PREFIX = "am-challenger.";
    private static final Pattern MEMORY_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{32}");

    public AutoMemoryVectorDocument {
        vectorId = required(vectorId, "vectorId");
        memoryId = required(memoryId, "memoryId");
        scope = Objects.requireNonNull(scope, "scope");
        kind = Objects.requireNonNull(kind, "kind");
        state = Objects.requireNonNull(state, "state");
        title = required(title, "title");
        canonicalText = required(canonicalText, "canonicalText");
        if (projectionRevision < 1) {
            throw new IllegalArgumentException("projectionRevision must be positive");
        }
        if ((kind == CandidateKind.CHALLENGER) != (state == CandidateState.CONFLICTING)) {
            throw new IllegalArgumentException("candidate kind and state are incompatible");
        }
    }

    public static AutoMemoryVectorDocument current(AutoMemory memory, long revision) {
        Objects.requireNonNull(memory, "memory");
        if (memory.status() == AutoMemoryStatus.DELETED) {
            throw new IllegalArgumentException("deleted Memory cannot be projected");
        }
        return new AutoMemoryVectorDocument(
                CURRENT_PREFIX + encoded(memory.memoryId()),
                memory.memoryId(),
                memory.scope(),
                CandidateKind.CURRENT,
                CandidateState.valueOf(memory.status().name()),
                memory.title(),
                memory.canonicalText(),
                revision);
    }

    public static AutoMemoryVectorDocument challenger(
            String memoryId,
            AutoMemoryScope scope,
            String title,
            String canonicalText,
            long revision
    ) {
        String normalizedMemoryId = required(memoryId, "memoryId");
        String normalizedText = required(canonicalText, "canonicalText");
        return new AutoMemoryVectorDocument(
                CHALLENGER_PREFIX + encoded(normalizedMemoryId) + "." + digest(normalizedText),
                normalizedMemoryId,
                scope,
                CandidateKind.CHALLENGER,
                CandidateState.CONFLICTING,
                title,
                normalizedText,
                revision);
    }

    /** Content is sent only to the embedding endpoint, never to Pinecone vector metadata. */
    public String retrievalText() {
        return title + "\n" + canonicalText;
    }

    /** Decodes only identities emitted by this projection contract; provider input is untrusted. */
    public static Optional<String> memoryIdFromVectorId(String vectorId) {
        if (vectorId == null || vectorId.isBlank() || vectorId.length() > 160) {
            return Optional.empty();
        }
        String encodedMemoryId;
        if (vectorId.startsWith(CURRENT_PREFIX)) {
            encodedMemoryId = vectorId.substring(CURRENT_PREFIX.length());
            if (encodedMemoryId.contains(".")) {
                return Optional.empty();
            }
        } else if (vectorId.startsWith(CHALLENGER_PREFIX)) {
            String suffix = vectorId.substring(CHALLENGER_PREFIX.length());
            int separator = suffix.lastIndexOf('.');
            if (separator < 1 || !DIGEST.matcher(suffix.substring(separator + 1)).matches()) {
                return Optional.empty();
            }
            encodedMemoryId = suffix.substring(0, separator);
        } else {
            return Optional.empty();
        }
        try {
            String memoryId = new String(
                    Base64.getUrlDecoder().decode(encodedMemoryId), StandardCharsets.UTF_8);
            return MEMORY_ID.matcher(memoryId).matches() && encoded(memoryId).equals(encodedMemoryId)
                    ? Optional.of(memoryId) : Optional.empty();
        } catch (IllegalArgumentException malformedBase64) {
            return Optional.empty();
        }
    }

    /** Accepts only the CURRENT identity used by generation-context retrieval. */
    public static Optional<String> currentMemoryIdFromVectorId(String vectorId) {
        if (vectorId == null || !vectorId.startsWith(CURRENT_PREFIX)) {
            return Optional.empty();
        }
        return memoryIdFromVectorId(vectorId);
    }

    public enum CandidateKind {
        CURRENT,
        CHALLENGER
    }

    public enum CandidateState {
        OBSERVED,
        ACTIVE,
        DISABLED,
        CONFLICTING
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
