package org.zipp.ai.domain.ingestion.model.valobj;

import java.time.Instant;
import java.util.Map;

public record BrowserPostPolicy(String url, Map<String, String> fields, Instant expiresAt) {

    public BrowserPostPolicy {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        url = url.trim();
        fields = Map.copyOf(fields);
        java.util.Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
