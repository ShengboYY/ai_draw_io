package org.zipp.ai.domain.chartbook.model.valobj;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.Objects;

/** Owner-fenced request to retain one existing Material inside a Chartbook. */
public record AddChartbookFileCommand(CatalogOwner owner, String chartbookId,
                                      String materialId, String idempotencyKey) {
    public AddChartbookFileCommand {
        owner = Objects.requireNonNull(owner, "owner");
        chartbookId = required(chartbookId, "chartbookId");
        materialId = required(materialId, "materialId");
        idempotencyKey = required(idempotencyKey, "Idempotency-Key");
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key is too long");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
