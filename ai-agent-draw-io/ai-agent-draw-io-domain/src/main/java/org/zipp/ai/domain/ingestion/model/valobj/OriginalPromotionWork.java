package org.zipp.ai.domain.ingestion.model.valobj;

public record OriginalPromotionWork(String uploadId, long uploadGeneration,
                                    String quarantineBucket, String quarantineKey,
                                    String quarantineVersionId, String destinationKey,
                                    String contentBlobId, String materialId, String versionId,
                                    String revisionId, String detectedMediaType,
                                    long byteSize, String contentSha256,
                                    PromotedOriginal fixedOriginal) {
    public OriginalPromotionWork {
        uploadId = requireText(uploadId, "uploadId");
        if (uploadGeneration < 0 || byteSize < 1) {
            throw new IllegalArgumentException("valid generation and byteSize are required");
        }
        quarantineBucket = requireText(quarantineBucket, "quarantineBucket");
        quarantineKey = requireText(quarantineKey, "quarantineKey");
        quarantineVersionId = requireText(quarantineVersionId, "quarantineVersionId");
        destinationKey = requireText(destinationKey, "destinationKey");
        contentBlobId = requireText(contentBlobId, "contentBlobId");
        materialId = requireText(materialId, "materialId");
        versionId = requireText(versionId, "versionId");
        revisionId = requireText(revisionId, "revisionId");
        detectedMediaType = requireText(detectedMediaType, "detectedMediaType");
        contentSha256 = requireText(contentSha256, "contentSha256");
        if (fixedOriginal != null && (!destinationKey.equals(fixedOriginal.objectKey())
                || byteSize != fixedOriginal.byteSize()
                || fixedOriginal.checksumSha256() == null)) {
            throw new IllegalArgumentException("fixed original must match the immutable content identity");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
