package org.zipp.ai.domain.material.model.valobj;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Content-free proof returned by an exact external deletion operation. */
public record MaterialDeletionReceipt(int deletedCount, String providerRequestIdsHash,
                                      String providerScope, boolean allRequestedHandled) {
    public MaterialDeletionReceipt {
        if (deletedCount < 0 || providerRequestIdsHash == null
                || !providerRequestIdsHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("deletion receipt is invalid");
        }
    }

    public static MaterialDeletionReceipt from(int deletedCount, List<String> providerRequestIds) {
        return create(deletedCount, providerRequestIds, null, true);
    }

    public static MaterialDeletionReceipt vector(int deletedCount, List<String> providerRequestIds,
                                                  String indexName, boolean allRequestedHandled) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("vector provider scope is required");
        }
        return create(deletedCount, providerRequestIds, indexName.trim(), allRequestedHandled);
    }

    private static MaterialDeletionReceipt create(int deletedCount, List<String> providerRequestIds,
                                                   String providerScope, boolean allRequestedHandled) {
        String joined = providerRequestIds == null || providerRequestIds.isEmpty()
                ? "no-provider-request" : String.join("\n", providerRequestIds);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
            return new MaterialDeletionReceipt(deletedCount, hash, providerScope, allRequestedHandled);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
