package org.zipp.ai.api.dto;

import java.util.Set;

public record ExcludedPagesRequestDTO(Set<Integer> pageNumbers) {
}
