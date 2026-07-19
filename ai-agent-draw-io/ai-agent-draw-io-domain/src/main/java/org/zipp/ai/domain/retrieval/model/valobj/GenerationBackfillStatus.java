package org.zipp.ai.domain.retrieval.model.valobj;

/** Immutable completeness snapshot used at the BUILDING/SHADOW/ACTIVE generation boundaries. */
public record GenerationBackfillStatus(String generationId, IndexGenerationState state,
                                       String activeGenerationId,
                                       long targetGeneration,
                                       int requiredRevisionCount, int readyRevisionCount,
                                       int expectedVectorCount, int indexedVectorCount,
                                       int readyManifestCount) {
    public GenerationBackfillStatus {
        generationId = requireText(generationId, "generationId");
        state = java.util.Objects.requireNonNull(state, "state");
        activeGenerationId = activeGenerationId == null ? null
                : requireText(activeGenerationId, "activeGenerationId");
        if (targetGeneration < 0 || requiredRevisionCount < 0 || readyRevisionCount < 0
                || expectedVectorCount < 0 || indexedVectorCount < 0 || readyManifestCount < 0
                || readyRevisionCount > requiredRevisionCount
                || readyManifestCount > requiredRevisionCount
                || indexedVectorCount > expectedVectorCount) {
            throw new IllegalArgumentException("generation backfill counts are invalid");
        }
    }

    public boolean complete() {
        return requiredRevisionCount > 0
                && readyRevisionCount == requiredRevisionCount
                && readyManifestCount == requiredRevisionCount
                && indexedVectorCount == expectedVectorCount;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
