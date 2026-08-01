package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryExtractionEligibilityPolicyTest {
    private final AutoMemoryExtractionEligibilityPolicy policy =
            new AutoMemoryExtractionEligibilityPolicy();

    @Test
    void skipsOnlyClearNonMemoryTurns() {
        assertFalse(policy.shouldExtract("你好"));
        assertFalse(policy.shouldExtract("Please tell me what you remember."));
        assertFalse(policy.shouldExtract("请告诉我你记住的全局绘图规则。"));
        assertFalse(policy.shouldExtract("Draw a login flowchart."));
        assertFalse(policy.shouldExtract("请帮我画一个登录流程图。"));
        assertFalse(policy.shouldExtract("Move this node to the left."));
        assertFalse(policy.shouldExtract("把这个节点移动到左边。"));
    }

    @Test
    void retainsDurableAndAmbiguousTurns() {
        assertTrue(policy.shouldExtract("以后都把这个节点放在左边。"));
        assertTrue(policy.shouldExtract("Always draw login flows from left to right."));
        assertTrue(policy.shouldExtract("I prefer this diagram's colors."));
        assertTrue(policy.shouldExtract("Change this; I hate rounded corners."));
        assertTrue(policy.shouldExtract("把这个改掉，我不喜欢圆角。"));
        assertTrue(policy.shouldExtract("Why is the current API node on the left?"));
        assertTrue(policy.shouldExtract("Keep labels concise."));
    }
}
