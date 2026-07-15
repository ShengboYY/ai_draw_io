package org.zipp.ai.domain.agent.model.valobj.analysis;

import java.util.Locale;

public enum CanvasIssueCategory {
    STRUCTURE,
    GEOMETRY,
    READABILITY,
    LAYOUT,
    STYLE,
    LIMITATION;

    public static CanvasIssueCategory fromLegacy(String value) {
        if (value == null || value.isBlank()) {
            return LIMITATION;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LIMITATION;
        }
    }
}
