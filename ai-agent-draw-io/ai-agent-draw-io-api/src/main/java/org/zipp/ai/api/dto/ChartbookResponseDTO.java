package org.zipp.ai.api.dto;

import java.time.Instant;
import java.util.Set;

public record ChartbookResponseDTO(String chartbookId, String name, String status,
                                   Set<String> diagramIds, Set<String> materialIds,
                                   Instant createdAt, Instant updatedAt) {
}
