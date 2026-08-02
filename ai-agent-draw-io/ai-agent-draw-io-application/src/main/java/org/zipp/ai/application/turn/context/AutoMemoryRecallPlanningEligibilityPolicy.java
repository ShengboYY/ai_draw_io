package org.zipp.ai.application.turn.context;

import java.util.Locale;
import java.util.Set;

/** Cheap, permissive gate that avoids a planning model call for clearly single-intent requests. */
public final class AutoMemoryRecallPlanningEligibilityPolicy {
    private static final Set<String> ENGLISH_SIGNALS = Set.of(
            " and ", " also ", " while ", " plus ", " but ", " whereas ", " even as ");
    private static final Set<String> CHINESE_SIGNALS = Set.of(
            "同时", "并且", "另外", "此外", "而且", "还要", "以及", "并把", "并将",
            "则", "一方面", "另一方面");

    public boolean shouldPlan(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return false;
        }
        String normalized = " " + userContent.trim().toLowerCase(Locale.ROOT) + " ";
        if (normalized.indexOf(';') >= 0 || normalized.indexOf('；') >= 0
                || normalized.indexOf('\n') >= 0) {
            return true;
        }
        return ENGLISH_SIGNALS.stream().anyMatch(normalized::contains)
                || CHINESE_SIGNALS.stream().anyMatch(normalized::contains);
    }
}
