package org.zipp.ai.domain.ingestion.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared deterministic identity for stages that cross asynchronous deployment boundaries. */
public final class ProcessingStageFingerprintPolicy {

    private ProcessingStageFingerprintPolicy() { }

    public static String extractionInput(String sourceSha256, String processingFingerprint) {
        String source = requireSha256(sourceSha256, "sourceSha256");
        String profile = requireSha256(processingFingerprint, "processingFingerprint");
        return sha256(source + ":" + profile + ":EXTRACT_NATIVE");
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
