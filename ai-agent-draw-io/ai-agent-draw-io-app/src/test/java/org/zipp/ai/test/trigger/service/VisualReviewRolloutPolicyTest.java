package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.zipp.ai.trigger.http.service.VisualReviewRolloutPolicy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VisualReviewRolloutPolicyTest {

    @Test
    public void springSelectsTheConfigurationConstructorWhenTestConstructorAlsoExists() {
        // Exercise the same component-instantiation path used during application startup.
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                VisualReviewRolloutPolicy.class)) {
            assertTrue(context.containsBean("visualReviewRolloutPolicy"));
        }
    }

    @Test
    public void reviewerAndRepairRoundsHaveIndependentRolloutBudgets() {
        VisualReviewRolloutPolicy reviewerOnly = new VisualReviewRolloutPolicy(true, true, 100, 0, 0);
        assertTrue(reviewerOnly.isReviewEnabled("usr-1", "diagram-1"));
        assertFalse(reviewerOnly.isAutoRepairEnabled("usr-1", "diagram-1", 1));

        VisualReviewRolloutPolicy firstRoundCanary = new VisualReviewRolloutPolicy(true, true, 100, 100, 0);
        assertTrue(firstRoundCanary.isAutoRepairEnabled("usr-1", "diagram-1", 1));
        assertFalse(firstRoundCanary.isAutoRepairEnabled("usr-1", "diagram-1", 2));

        VisualReviewRolloutPolicy bothRounds = new VisualReviewRolloutPolicy(true, true, 100, 100, 100);
        assertTrue(bothRounds.isAutoRepairEnabled("usr-1", "diagram-1", 2));
    }

    @Test
    public void cohortAssignmentIsStableAndGlobalFlagsFailClosed() {
        VisualReviewRolloutPolicy disabled = new VisualReviewRolloutPolicy(false, true, 100, 100, 100);
        assertFalse(disabled.isReviewEnabled("usr-1", "diagram-1"));
        assertFalse(disabled.isAutoRepairEnabled("usr-1", "diagram-1", 1));

        VisualReviewRolloutPolicy canary = new VisualReviewRolloutPolicy(true, true, 37, 37, 37);
        assertTrue(canary.isReviewEnabled("owner-2", "diagram-1"));
        assertTrue(canary.isReviewEnabled("owner-2", "diagram-1"));
        assertFalse(canary.isReviewEnabled("owner-1", "diagram-1"));

        VisualReviewRolloutPolicy nestedCanary = new VisualReviewRolloutPolicy(true, true, 50, 10, 0);
        assertTrue(nestedCanary.isReviewEnabled("owner-3", "diagram-1"));
        assertFalse(nestedCanary.isAutoRepairEnabled("owner-3", "diagram-1", 1));
        assertTrue(nestedCanary.isAutoRepairEnabled("owner-2", "diagram-1", 1));
        assertFalse(canary.isAutoRepairEnabled("", "diagram-stable", 1));
        assertFalse(canary.isAutoRepairEnabled("usr-stable", "diagram-stable", 3));
    }
}
