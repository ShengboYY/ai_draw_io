package org.zipp.ai.api.dto;

import java.util.List;

public record MaterialCatalogPageDTO(List<MaterialCatalogCardDTO> items, long total,
                                     int limit, int offset) {
}
