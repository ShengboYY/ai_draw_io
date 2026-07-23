package org.zipp.ai.ingestion.worker.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Query-only rewrite that asks the embedding model for direct source evidence without guessing an answer. */
final class ResearchQueryRewriter {
    static final String FINGERPRINT =
            "drawio-bilingual-evidence-focused-v2:han-aware-prefix:frozen-domain-terms";
    private static final String CHINESE_PREFIX =
            "查找资料中包含可直接回答该请求的事实、规则、数值或步骤的原文：";
    private static final String ENGLISH_PREFIX =
            "Find the source passage containing the facts, rules, values, or steps that directly "
                    + "answer this request: ";
    private static final String TERM_PREFIX = " Cross-language Draw.io retrieval terms: ";

    private ResearchQueryRewriter() { }

    static String rewrite(String query) {
        String value = query == null ? "" : query.trim();
        boolean containsHan = value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        String rewritten = (containsHan ? CHINESE_PREFIX : ENGLISH_PREFIX) + value;
        if (containsHan) return rewritten;
        List<String> terms = bilingualTerms(value);
        return terms.isEmpty() ? rewritten : rewritten + TERM_PREFIX + String.join("; ", terms);
    }

    private static List<String> bilingualTerms(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        // The lexicon is source-independent and keyed only by model-visible request phrases.
        if (normalized.contains("threshold")) terms.add("threshold 阈值");
        if (normalized.contains("human review")) terms.add("human review 人工复核");
        if (normalized.contains("escalation")) terms.add("escalation 升级");
        if (normalized.contains("vector search")) terms.add("vector search 向量检索");
        if (normalized.contains("object storage")) terms.add("object storage 对象存储");
        if (normalized.contains("canvas save")) terms.add("canvas save 画布保存");
        if (normalized.contains("incident") || normalized.matches(".*\\bsev-?\\d+\\b.*")) {
            terms.add("incident 事件");
            terms.add("severity 严重级别");
        }
        return List.copyOf(terms);
    }
}
