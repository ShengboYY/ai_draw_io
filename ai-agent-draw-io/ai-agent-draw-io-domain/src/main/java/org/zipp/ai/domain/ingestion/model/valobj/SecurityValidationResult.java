package org.zipp.ai.domain.ingestion.model.valobj;

public record SecurityValidationResult(long actualSize,
                                       String actualSha256,
                                       String detectedMediaType,
                                       Integer pageCount,
                                       Long pixelCount) {
    public SecurityValidationResult {
        if (actualSize < 1 || actualSha256 == null || !actualSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("validated size and SHA-256 are required");
        }
        if (detectedMediaType == null || detectedMediaType.isBlank()) {
            throw new IllegalArgumentException("detectedMediaType is required");
        }
    }
}
