package org.zipp.ai.domain.agent.model.valobj.analysis;

import java.util.Locale;

public enum CanvasIssueSeverity {
    MINOR,
    MAJOR,
    CRITICAL;

    public static CanvasIssueSeverity fromLegacy(String value) {
        if (value == null || value.isBlank()) {
            return MINOR;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MINOR;
        }
    }
}
