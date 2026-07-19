package org.zipp.ai.domain.retrieval.projection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deployment-owned vector configuration; it deliberately stays outside the ProcessingRevision fingerprint. */
public record VectorGenerationProfile(String indexName, String namespace, String embeddingModel,
                                      String embeddingModelFingerprint, int dimension, String metric,
                                      String vectorSchemaVersion, String tokenizerFingerprint) {
    public VectorGenerationProfile {
        indexName = requireText(indexName, "indexName");
        namespace = requireText(namespace, "namespace");
        embeddingModel = requireText(embeddingModel, "embeddingModel");
        if (embeddingModelFingerprint == null || !embeddingModelFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("embeddingModelFingerprint must be lowercase SHA-256");
        }
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        metric = requireText(metric, "metric");
        vectorSchemaVersion = requireText(vectorSchemaVersion, "vectorSchemaVersion");
        tokenizerFingerprint = requireText(tokenizerFingerprint, "tokenizerFingerprint");
    }

    public String generationFingerprint() {
        return sha256(indexName + ":" + namespace + ":" + embeddingModel + ":"
                + embeddingModelFingerprint + ":" + dimension + ":" + metric + ":"
                + vectorSchemaVersion);
    }

    public String generationId() {
        return "ig_" + generationFingerprint().substring(0, 24);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
