package org.zipp.ai.domain.material.model.valobj;

import java.util.List;

public record MaterialCatalogPage(List<MaterialCatalogItem> items, long total, int limit, int offset) {
    public MaterialCatalogPage {
        items = List.copyOf(items);
        if (total < 0 || limit < 1 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("catalog page bounds are invalid");
        }
    }
}
