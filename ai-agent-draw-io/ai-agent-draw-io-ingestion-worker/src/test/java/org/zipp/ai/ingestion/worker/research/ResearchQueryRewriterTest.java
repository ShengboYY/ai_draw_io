package org.zipp.ai.ingestion.worker.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchQueryRewriterTest {

    @Test
    void shouldUseAChineseEvidenceInstructionForHanQueries() {
        assertEquals("查找资料中包含可直接回答该请求的事实、规则、数值或步骤的原文：用户点名资料后如何限制范围？",
                ResearchQueryRewriter.rewrite("用户点名资料后如何限制范围？"));
    }

    @Test
    void shouldUseAnEnglishEvidenceInstructionForLatinQueries() {
        assertEquals("Find the source passage containing the facts, rules, values, or steps that directly "
                        + "answer this request: What is the rollback threshold?",
                ResearchQueryRewriter.rewrite("What is the rollback threshold?"));
    }

    @Test
    void shouldTrimTheOriginalQueryWithoutChangingItsContent() {
        assertEquals("Find the source passage containing the facts, rules, values, or steps that directly "
                        + "answer this request: Who owns Canvas Composer?",
                ResearchQueryRewriter.rewrite("  Who owns Canvas Composer?  "));
    }
}
