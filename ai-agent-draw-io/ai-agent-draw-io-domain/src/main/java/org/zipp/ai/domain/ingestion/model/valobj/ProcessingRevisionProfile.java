package org.zipp.ai.domain.ingestion.model.valobj;

/** Immutable processing identity persisted with a revision for reproducibility and audit. */
public record ProcessingRevisionProfile(String fingerprint, String parserVersion, String cleanerVersion,
                                        String chunkSchemaVersion, String ocrVersion,
                                        String vlmSchemaVersion) {
    public ProcessingRevisionProfile {
        fingerprint = requireFingerprint(fingerprint);
        parserVersion = requireVersion(parserVersion, "parserVersion");
        cleanerVersion = requireVersion(cleanerVersion, "cleanerVersion");
        chunkSchemaVersion = requireVersion(chunkSchemaVersion, "chunkSchemaVersion");
        ocrVersion = requireVersion(ocrVersion, "ocrVersion");
        vlmSchemaVersion = requireVersion(vlmSchemaVersion, "vlmSchemaVersion");
    }

    private static String requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be lowercase SHA-256");
        }
        return value;
    }

    private static String requireVersion(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException(field + " must contain at most 64 characters");
        }
        return value.trim();
    }
}
