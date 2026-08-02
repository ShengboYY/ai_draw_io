package org.zipp.ai.application.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

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
                "am-current." + encoded(memory.memoryId()),
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
                "am-challenger." + encoded(normalizedMemoryId) + "." + digest(normalizedText),
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
