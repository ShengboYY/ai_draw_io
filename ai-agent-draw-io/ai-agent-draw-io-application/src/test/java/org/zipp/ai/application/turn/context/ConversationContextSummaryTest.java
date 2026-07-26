package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextSummaryTest {

    @Test
    void summaryIsDeterministicAndBoundToThePinnedHighWater() {
        String first = ConversationContextSummary.rebuild(List.of("user: draw", "agent: done"), 12);
        String same = ConversationContextSummary.rebuild(List.of("user: draw", "agent: done"), 12);
        String changed = ConversationContextSummary.rebuild(List.of("user: draw", "agent: done"), 13);

        assertEquals(first, same);
        assertFalse(first.equals(changed));
        assertTrue(first.contains("messageHighWater=12"));
        assertTrue(first.contains("latest=agent: done"));
    }

    @Test
    void summaryStaysBoundedForLargeRecentMessages() {
        String summary = ConversationContextSummary.rebuild(
                List.of("agent: " + "x".repeat(4_000)), 12);

        assertTrue(summary.length() <= 2_000);
    }
}
