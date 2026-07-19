package org.zipp.ai.domain.ingestion.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.HexFormat;

/** Shared deterministic identity for stages that cross asynchronous deployment boundaries. */
public final class ProcessingStageFingerprintPolicy {

    private ProcessingStageFingerprintPolicy() { }

    public static String extractionInput(String sourceSha256, String processingFingerprint) {
        String source = requireSha256(sourceSha256, "sourceSha256");
        String profile = requireSha256(processingFingerprint, "processingFingerprint");
        return sha256(source + ":" + profile + ":EXTRACT_NATIVE");
    }

    public static String structureSeed(String processingFingerprint) {
        return sha256(requireSha256(processingFingerprint, "processingFingerprint")
                + ":BUILD_DOCUMENT_STRUCTURE:structure-v1");
    }

    public static String structureInput(List<String> orderedCanonicalHashes, String processingFingerprint) {
        List<String> hashes = List.copyOf(orderedCanonicalHashes);
        if (hashes.isEmpty()) {
            throw new IllegalArgumentException("orderedCanonicalHashes must not be empty");
        }
        hashes.forEach(hash -> requireSha256(hash, "canonicalHash"));
        return sha256(String.join(":", hashes) + ":" + structureSeed(processingFingerprint));
    }

    public static String visualInput(String structureHash, String structureArtifactHash,
                                     String processingFingerprint) {
        return sha256(requireSha256(structureHash, "structureHash") + ":"
                + requireSha256(structureArtifactHash, "structureArtifactHash") + ":"
                + requireSha256(processingFingerprint, "processingFingerprint")
                + ":ANALYZE_VISUALS:visual-schema-v1");
    }

    public static String evidenceInput(String visualManifestHash, String processingFingerprint) {
        return sha256(requireSha256(visualManifestHash, "visualManifestHash") + ":"
                + requireSha256(processingFingerprint, "processingFingerprint")
                + ":BUILD_EVIDENCE_UNITS:evidence-schema-v1");
    }

    public static String retrievalInput(String evidenceManifestHash, String processingFingerprint) {
        return sha256(requireSha256(evidenceManifestHash, "evidenceManifestHash") + ":"
                + requireSha256(processingFingerprint, "processingFingerprint")
                + ":BUILD_RETRIEVAL_CHUNKS:retrieval-schema-v1");
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
