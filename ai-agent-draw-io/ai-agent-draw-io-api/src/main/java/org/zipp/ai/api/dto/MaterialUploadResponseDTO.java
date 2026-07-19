package org.zipp.ai.api.dto;

import java.time.Instant;
import java.util.Map;

public record MaterialUploadResponseDTO(String uploadId,
                                        String state,
                                        BrowserPostPolicyDTO postPolicy,
                                        String pinnedObjectVersionId,
                                        String materialId,
                                        String versionId,
                                        String errorCode) {
    public record BrowserPostPolicyDTO(String url, Map<String, String> fields, Instant expiresAt) {
    }
}
