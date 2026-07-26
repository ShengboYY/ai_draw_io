package org.zipp.ai.api.dto;

import java.util.List;
import java.util.Map;

/** Nullable fields mean "keep" for PATCH; empty strings/maps/lists explicitly clear a field. */
public record ChartbookProfileRequestDTO(
        String instructions,
        String goal,
        String summary,
        Map<String, String> glossary,
        Map<String, String> defaultStyle,
        List<String> stableConstraints
) {
}
