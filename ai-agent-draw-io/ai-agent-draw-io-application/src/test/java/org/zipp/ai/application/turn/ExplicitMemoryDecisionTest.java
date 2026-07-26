package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplicitMemoryDecisionTest {

    @Test
    void onlyTheExplicitChineseActionCreatesAMemoryDeclaration() {
        ExplicitMemoryDecision decision = ExplicitMemoryDecision
                .fromUserContent("请记住这个决定：所有服务使用事件命名约定", "chartbook-1")
                .orElseThrow();

        assertEquals("remembered-decision", decision.decisionKey());
        assertEquals("所有服务使用事件命名约定", decision.canonicalText());
        assertEquals(decision.declarationDigest(), decision.declaration().digest().value());
        assertTrue(decision.candidateId(new TurnKey("owner", "conversation", "turn"))
                .startsWith("memory-"));
    }

    @Test
    void ordinaryChatCannotCreateMemory() {
        assertFalse(ExplicitMemoryDecision.fromUserContent("请帮我画一个登录流程", "chartbook-1").isPresent());
    }

    @Test
    void acceptsVersionedChineseAndEnglishExplicitPhrasesOnlyWhenTargetIsBound() {
        assertTrue(ExplicitMemoryDecision.fromUserContent(
                "记住这个决定: 使用事件命名", "chartbook-1").isPresent());
        assertTrue(ExplicitMemoryDecision.fromUserContent(
                "remember this decision: use event naming", "chartbook-1").isPresent());
        assertFalse(ExplicitMemoryDecision.fromUserContent(
                "remember this decision: use event naming", null).isPresent());
    }
}
