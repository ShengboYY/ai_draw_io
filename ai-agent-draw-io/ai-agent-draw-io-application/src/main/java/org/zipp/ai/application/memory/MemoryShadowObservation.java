package org.zipp.ai.application.memory;

/**
 * Content-safe observation for evaluation. Only a unique accepted shadow candidate carries its
 * sanitized text; rejected and conflicting candidates never echo candidate content.
 */
public record MemoryShadowObservation(
        String candidateRef,
        MemoryShadowCandidateStatus status,
        String decisionKey,
        String applicabilityStage,
        String canonicalText,
        String reasonCode
) {
    public MemoryShadowObservation {
        if (candidateRef == null || candidateRef.isBlank() || status == null) {
            throw new IllegalArgumentException("shadow observation identity is invalid");
        }
        if (status == MemoryShadowCandidateStatus.SHADOW_ACCEPTED && (canonicalText == null
                || canonicalText.isBlank() || reasonCode != null)) {
            throw new IllegalArgumentException("accepted shadow observation must contain only safe text");
        }
        if (status != MemoryShadowCandidateStatus.SHADOW_ACCEPTED && canonicalText != null) {
            throw new IllegalArgumentException("non-accepted shadow observation must not contain text");
        }
        if (status == MemoryShadowCandidateStatus.REJECTED && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("rejected shadow observation must contain a safe reason");
        }
        if (status != MemoryShadowCandidateStatus.REJECTED && reasonCode != null) {
            throw new IllegalArgumentException("only rejected shadow observations contain a reason");
        }
    }
}
