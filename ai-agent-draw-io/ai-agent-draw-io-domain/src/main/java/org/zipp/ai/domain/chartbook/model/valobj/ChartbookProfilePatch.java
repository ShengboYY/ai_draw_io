package org.zipp.ai.domain.chartbook.model.valobj;

import java.util.List;
import java.util.Map;

/** PATCH semantics: null keeps the current field; an empty value explicitly clears it. */
public record ChartbookProfilePatch(
        String instructions,
        String goal,
        String summary,
        Map<String, String> glossary,
        DiagramStyleDefaults defaultStyle,
        List<String> stableConstraints
) {

    public boolean hasChanges() {
        return instructions != null || goal != null || summary != null || glossary != null
                || defaultStyle != null || stableConstraints != null;
    }
}
