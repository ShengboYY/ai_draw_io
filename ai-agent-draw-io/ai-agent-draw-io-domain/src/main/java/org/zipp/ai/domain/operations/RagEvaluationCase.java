package org.zipp.ai.domain.operations;

import java.util.List;
import java.util.Objects;

/** Synthetic, immutable RAG fixture contract; aliases prevent production owner identifiers entering git. */
public record RagEvaluationCase(String schemaVersion, String caseId, String datasetVersion,
                                String category, String language, boolean locked,
                                OwnerFixture ownerFixture, String queryShape, String targetKind,
                                String query, String expectedRoute,
                                List<String> allowedSourceVersions,
                                List<String> goldEvidenceIds,
                                List<String> hardNegativeIds,
                                List<String> requiredFacets,
                                List<String> forbiddenSourceIds,
                                boolean visualVerificationRequired,
                                boolean canvasMutationAllowed,
                                List<String> expectedCitationClaims) {
    public RagEvaluationCase {
        if (!"rag-eval-case-v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported RAG evaluation case schema");
        }
        caseId = requireText(caseId, "caseId");
        datasetVersion = requireText(datasetVersion, "datasetVersion");
        category = requireText(category, "category");
        language = requireText(language, "language");
        ownerFixture = Objects.requireNonNull(ownerFixture, "ownerFixture");
        queryShape = requireText(queryShape, "queryShape");
        targetKind = requireText(targetKind, "targetKind");
        query = requireText(query, "query");
        expectedRoute = requireText(expectedRoute, "expectedRoute");
        allowedSourceVersions = requiredList(allowedSourceVersions, "allowedSourceVersions");
        goldEvidenceIds = requiredList(goldEvidenceIds, "goldEvidenceIds");
        hardNegativeIds = safeList(hardNegativeIds);
        requiredFacets = requiredList(requiredFacets, "requiredFacets");
        forbiddenSourceIds = safeList(forbiddenSourceIds);
        expectedCitationClaims = requiredList(expectedCitationClaims, "expectedCitationClaims");
    }

    public record OwnerFixture(String ownerType, String ownerKeyAlias,
                               String scopeType, String scopeKeyAlias) {
        public OwnerFixture {
            ownerType = requireText(ownerType, "ownerType");
            ownerKeyAlias = requireText(ownerKeyAlias, "ownerKeyAlias");
            scopeType = requireText(scopeType, "scopeType");
            scopeKeyAlias = requireText(scopeKeyAlias, "scopeKeyAlias");
            String normalized = ownerKeyAlias.toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("usr_") || normalized.startsWith("anon_")
                    || normalized.startsWith("acc_")) {
                throw new IllegalArgumentException("ownerKeyAlias must be a synthetic alias");
            }
        }
    }

    private static List<String> requiredList(List<String> values, String field) {
        List<String> result = safeList(values);
        if (result.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return result;
    }

    private static List<String> safeList(List<String> values) {
        if (values == null) return List.of();
        List<String> copy = values.stream().map(value -> requireText(value, "list value")).distinct().toList();
        return List.copyOf(copy);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
