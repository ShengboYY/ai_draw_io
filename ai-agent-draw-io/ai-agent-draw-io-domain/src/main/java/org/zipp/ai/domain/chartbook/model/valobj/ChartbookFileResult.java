package org.zipp.ai.domain.chartbook.model.valobj;

import org.zipp.ai.domain.material.model.valobj.MaterialCatalogDetails;

import java.util.Objects;

/** Final owner-safe file and Chartbook projections returned after an idempotent mutation. */
public record ChartbookFileResult(ChartbookView chartbook, MaterialCatalogDetails file) {
    public ChartbookFileResult {
        chartbook = Objects.requireNonNull(chartbook, "chartbook");
        file = Objects.requireNonNull(file, "file");
    }
}
