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
        assertTrue(decision.declaration().hasPinnedProposal());
        assertEquals("zh-Hans", decision.declaration().locale());
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

    @Test
    void localeRulePackSupportsNaturalHighPrecisionConfirmations() {
        assertEquals("zh-Hant", locale("請記住這個決定：服務使用事件命名"));
        assertEquals("en", locale("Please record this decision: services use event naming"));
        assertEquals("es", locale("Recuerda esta decisión: usar nombres de eventos"));
        assertEquals("fr", locale("Mémorise cette décision : utiliser les noms d’événement"));
        assertEquals("de", locale("Bitte speichere diese Entscheidung: Ereignisnamen verwenden"));
        assertEquals("ja", locale("この決定を覚えてください：イベント命名を使う"));
        assertEquals("ko", locale("이 결정을 기억해줘: 이벤트 이름을 사용한다"));
    }

    @Test
    void localeRulePackRejectsMemoryVerbWithoutDecisionAuthority() {
        assertFalse(ExplicitMemoryDecision.fromUserContent(
                "Please remember to draw a flowchart", "chartbook-1").isPresent());
        assertFalse(ExplicitMemoryDecision.fromUserContent(
                "请记住帮我画流程图", "chartbook-1").isPresent());
    }

    private String locale(String content) {
        return ExplicitMemoryDecision.fromUserContent(content, "chartbook-1")
                .orElseThrow()
                .declaration()
                .locale();
    }
}
