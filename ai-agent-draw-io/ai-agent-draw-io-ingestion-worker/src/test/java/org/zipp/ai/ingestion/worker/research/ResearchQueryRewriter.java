package org.zipp.ai.ingestion.worker.research;

/** Query-only rewrite that asks the embedding model for direct source evidence without guessing an answer. */
final class ResearchQueryRewriter {
    static final String FINGERPRINT = "evidence-focused-v1:han-aware-direct-source-evidence-prefix";
    private static final String CHINESE_PREFIX =
            "查找资料中包含可直接回答该请求的事实、规则、数值或步骤的原文：";
    private static final String ENGLISH_PREFIX =
            "Find the source passage containing the facts, rules, values, or steps that directly "
                    + "answer this request: ";

    private ResearchQueryRewriter() { }

    static String rewrite(String query) {
        String value = query == null ? "" : query.trim();
        boolean containsHan = value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        return (containsHan ? CHINESE_PREFIX : ENGLISH_PREFIX) + value;
    }
}
