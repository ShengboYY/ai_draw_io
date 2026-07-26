package org.zipp.ai.domain.chartbook.model.valobj;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded style defaults; arbitrary large preferences JSON is deliberately not a Profile API. */
public record DiagramStyleDefaults(Map<String, String> values) {

    public DiagramStyleDefaults {
        values = copy(values, 32, 64, 256, "defaultStyle");
    }

    public static DiagramStyleDefaults empty() {
        return new DiagramStyleDefaults(Map.of());
    }

    private static Map<String, String> copy(
            Map<String, String> source, int countLimit, int keyLimit, int valueLimit, String field) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key == null || key.isBlank() || key.length() > keyLimit
                        || value == null || value.length() > valueLimit) {
                    throw new IllegalArgumentException(field + " contains an invalid entry");
                }
                copy.put(key.trim(), value.trim());
            });
        }
        if (copy.size() > countLimit) {
            throw new IllegalArgumentException(field + " exceeds the bounded entry limit");
        }
        return Map.copyOf(copy);
    }
}
