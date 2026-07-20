package org.zipp.ai.domain.chartbook.model.valobj;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

public record CreateChartbookCommand(CatalogOwner owner, String idempotencyKey, String name) {
    public CreateChartbookCommand {
        if (owner == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128 || name == null || name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("create chartbook command is invalid");
        }
        idempotencyKey = idempotencyKey.trim();
        name = name.trim();
    }
}
