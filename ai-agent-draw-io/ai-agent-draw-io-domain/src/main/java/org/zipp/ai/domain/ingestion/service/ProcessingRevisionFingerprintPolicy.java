package org.zipp.ai.domain.ingestion.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Separates full processing identity from the Worker runtime profile used for queue routing. */
public final class ProcessingRevisionFingerprintPolicy {
    private ProcessingRevisionFingerprintPolicy() { }

    public static String processingFingerprint(String workerProfileFingerprint,
                                               Set<Integer> excludedPages) {
        if (workerProfileFingerprint == null || !workerProfileFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("workerProfileFingerprint must be lowercase SHA-256");
        }
        String excluded = new TreeSet<>(excludedPages).stream().map(String::valueOf)
                .collect(Collectors.joining(","));
        return sha256(workerProfileFingerprint + ":excluded=" + excluded);
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
