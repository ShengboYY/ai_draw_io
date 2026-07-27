package org.zipp.ai.application.turn.classification;

import org.zipp.ai.application.turn.demand.Confidence;

import java.util.List;

/** Untrusted source semantics returned with the primary routing decision. */
public record SemanticSourceIntent(
        SourceIntentKind kind,
        Confidence confidence,
        List<String> attachmentRefs,
        String relevanceQuery,
        String safeReason
) {

    public SemanticSourceIntent {
        if (kind == null || confidence == null || safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("semantic source intent values must not be blank");
        }
        attachmentRefs = List.copyOf(attachmentRefs == null ? List.of() : attachmentRefs);
        relevanceQuery = relevanceQuery == null || relevanceQuery.isBlank()
                ? null : relevanceQuery.trim();
        safeReason = safeReason.trim();
    }

    public static SemanticSourceIntent none() {
        return new SemanticSourceIntent(
                SourceIntentKind.NO_SOURCE,
                Confidence.HIGH,
                List.of(),
                null,
                "The current request does not ask to use sources.");
    }
}
