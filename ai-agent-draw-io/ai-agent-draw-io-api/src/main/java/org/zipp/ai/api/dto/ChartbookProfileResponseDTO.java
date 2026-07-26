package org.zipp.ai.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChartbookProfileResponseDTO(
        String chartbookId,
        long version,
        String instructions,
        String goal,
        String summary,
        Map<String, String> glossary,
        Map<String, String> defaultStyle,
        List<String> stableConstraints,
        String profileState,
        Instant updatedAt
) {
}
