package org.zipp.ai.api.dto;

import java.time.Instant;
import java.util.List;

public record MaterialCatalogDetailsDTO(MaterialCatalogCardDTO material,
                                        List<VersionDTO> versions,
                                        List<ScopeDTO> scopes) {
    public record VersionDTO(String versionId, int versionNo, String detectedMime,
                             long byteSize, Integer pageCount, String processingStatus,
                             int progress, Instant createdAt) {
    }

    public record ScopeDTO(String linkId, String scopeType, String scopeKey) {
    }
}
