package org.zipp.ai.domain.retrieval.internal;

import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.RetrievalRoute;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic absolute gate kept separate from relative RRF ranking. */
final class EvidenceSufficiencyEvaluator {
    private static final Pattern LATIN_OR_NUMBER = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{2,}|\\d+(?:\\.\\d+)?");
    private static final Set<String> STOP_TERMS = Set.of("please", "according", "based", "source", "sources", "and",
            "document", "answer", "what", "how", "summarize", "summary", "diagram", "draw", "compare");
    private static final List<String> CJK_STOP_PHRASES = List.of(
            "请帮我", "请帮", "帮我", "根据", "资料", "回答", "总结", "梳理", "整份", "文档", "绘图", "流程图", "流程", "比较");

    Result evaluate(String request, RetrievalRoute route, List<EvidenceBundleItem> items) {
        if (items == null || items.isEmpty()) {
            return Result.unsupported("NO_DISPLAY_EVIDENCE", "requested fact or relationship");
        }
        if (route == RetrievalRoute.VISUAL || route == RetrievalRoute.VISUAL_EXACT) {
            return items.stream().anyMatch(item -> "VISUAL".equals(item.modality()))
                    ? Result.supported()
                    : Result.unsupported("VISUAL_VERIFICATION_REQUIRED", "requested visual structure");
        }
        String evidence = normalize(items.stream().map(EvidenceBundleItem::text)
                .filter(Objects::nonNull).reduce("", (left, right) -> left + " " + right));
        String query = normalize(request);

        List<String> exacts = tokens(query).stream().filter(token -> token.matches(".*\\d.*")).toList();
        Optional<String> missingExact = exacts.stream().filter(token -> !evidence.contains(token)).findFirst();
        if (missingExact.isPresent()) {
            return Result.unsupported("REQUIRED_EXACT_TERM_MISSING", missingExact.get());
        }
        List<String> terms = tokens(query).stream().filter(token -> !STOP_TERMS.contains(token)).toList();
        long matchedTerms = terms.stream().filter(evidence::contains).count();
        String missingTerm = terms.stream().filter(term -> !evidence.contains(term)).findFirst()
                .orElse("requested fact or relationship");
        boolean broadSummary = containsAny(query, "总结", "梳理", "整份", "summarize", "summary", "overview");
        if (broadSummary) {
            int characters = items.stream().mapToInt(item -> item.text() == null ? 0 : item.text().length()).sum();
            if (!terms.isEmpty() && matchedTerms == 0) {
                return Result.unsupported("ABSOLUTE_RELEVANCE_TOO_LOW", missingTerm);
            }
            return items.size() >= 2 && characters >= 160
                    ? Result.supported() : Result.unsupported("SUMMARY_COVERAGE_TOO_SMALL", "document summary");
        }
        int requiredMatches = terms.isEmpty() ? 0 : Math.max(1, (int) Math.ceil(terms.size() * 0.60));
        if (matchedTerms < requiredMatches) {
            return Result.unsupported("ABSOLUTE_RELEVANCE_TOO_LOW", missingTerm);
        }
        return Result.supported();
    }

    private List<String> tokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = LATIN_OR_NUMBER.matcher(value);
        while (matcher.find()) tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        // CJK bigrams provide a conservative language-independent overlap signal without adding a
        // tokenizer dependency to the online domain module.
        String cjk = value.replaceAll("[^\\p{IsHan}]", "");
        for (String stopPhrase : CJK_STOP_PHRASES) cjk = cjk.replace(stopPhrase, "");
        for (int index = 0; index + 2 <= cjk.length(); index++) {
            tokens.add(cjk.substring(index, index + 2));
        }
        return List.copyOf(tokens);
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    record Result(boolean sufficient, String gap, String missingSubject) {
        static Result supported() {
            return new Result(true, "", "");
        }

        static Result unsupported(String gap, String missingSubject) {
            return new Result(false, gap, missingSubject);
        }
    }
}
