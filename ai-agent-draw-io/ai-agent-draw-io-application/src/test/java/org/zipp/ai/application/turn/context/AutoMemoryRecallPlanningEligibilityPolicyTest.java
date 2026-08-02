package org.zipp.ai.application.turn.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryRecallPlanningEligibilityPolicyTest {
    private final AutoMemoryRecallPlanningEligibilityPolicy policy =
            new AutoMemoryRecallPlanningEligibilityPolicy();

    @Test
    void allowsBroadCoordinationSignalsForModelClassification() {
        assertTrue(policy.shouldPlan("Use blue nodes and keep retry paths dashed"));
        assertTrue(policy.shouldPlan("节点纵向排列，同时把队列填充成紫色"));
        assertTrue(policy.shouldPlan("Keep labels short; leave more whitespace"));
    }

    @Test
    void skipsClearlySingleIntentRequests() {
        assertFalse(policy.shouldPlan("Use concise labels"));
        assertFalse(policy.shouldPlan("把审批节点放在顶部"));
        assertFalse(policy.shouldPlan("  "));
    }
}
