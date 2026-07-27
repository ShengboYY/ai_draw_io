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
                        + "answer this request: What is the rollback threshold? "
                        + "Cross-language Draw.io retrieval terms: threshold 阈值",
                ResearchQueryRewriter.rewrite("What is the rollback threshold?"));
    }

    @Test
    void shouldTrimTheOriginalQueryWithoutChangingItsContent() {
        assertEquals("Find the source passage containing the facts, rules, values, or steps that directly "
                        + "answer this request: Who owns Canvas Composer?",
                ResearchQueryRewriter.rewrite("  Who owns Canvas Composer?  "));
    }

    @Test
    void shouldAppendFrozenBilingualTermsForDrawioThresholdRequests() {
        assertEquals("Find the source passage containing the facts, rules, values, or steps that directly "
                        + "answer this request: Create a threshold gate for escalation to human review. "
                        + "Cross-language Draw.io retrieval terms: threshold 阈值; human review 人工复核; "
                        + "escalation 升级",
                ResearchQueryRewriter.rewrite(
                        "Create a threshold gate for escalation to human review."));
    }

    @Test
    void shouldAppendFrozenFailureAndIncidentTermsWithoutReadingTaskMetadata() {
        assertEquals("Find the source passage containing the facts, rules, values, or steps that directly "
                        + "answer this request: Compare vector search, object storage, canvas save, and SEV-3. "
                        + "Cross-language Draw.io retrieval terms: vector search 向量检索; "
                        + "object storage 对象存储; canvas save 画布保存; incident 事件; severity 严重级别",
                ResearchQueryRewriter.rewrite(
                        "Compare vector search, object storage, canvas save, and SEV-3."));
    }
}
