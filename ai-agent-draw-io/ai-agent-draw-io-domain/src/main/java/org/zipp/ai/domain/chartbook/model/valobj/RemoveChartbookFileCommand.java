package org.zipp.ai.domain.chartbook.model.valobj;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.Objects;

/** Owner-fenced idempotent request to remove one Material from a Chartbook. */
public record RemoveChartbookFileCommand(CatalogOwner owner, String chartbookId,
                                         String materialId, String idempotencyKey) {
    public RemoveChartbookFileCommand {
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
