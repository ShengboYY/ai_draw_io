package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.TextBlockKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Deterministic, source-independent classification for text blocks with strong local signals. */
public final class TextBlockKindPolicy {

    private static final Pattern BULLET_PREFIX = Pattern.compile(
            "^[-*\\u2022\\u2023\\u25aa\\u25e6]\\s+\\S.*$");
    private static final Pattern ORDERED_LIST_PREFIX = Pattern.compile(
            "^(?:\\d+|[a-zA-Z])[.)]\\s+\\S.*$");
    private static final Pattern CAPTION_PREFIX = Pattern.compile(
            "^(?:figure|fig\\.?|table|chart|diagram|图|表)\\s*[\\d一二三四五六七八九十]+(?:[.：:]|\\s).*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?。！？;；:]$");
    private static final double MINIMUM_HEADING_HEIGHT = 0.018;

    public String fingerprint() {
        return "text-block-kind-v2:caption-prefix:consistent-multiline-table:bullet-prefix:"
                + "heading-height=.018-max120:ordered-list-prefix";
    }

    public TextBlockKind classify(TextBlockKind declaredKind, String text, double relativeLineHeight) {
        TextBlockKind declared = Objects.requireNonNull(declaredKind, "declaredKind");
        if (declared != TextBlockKind.PARAGRAPH) {
            return declared;
        }
        String normalized = Objects.requireNonNull(text, "text").strip();
        if (CAPTION_PREFIX.matcher(normalized).matches()) {
            return TextBlockKind.CAPTION;
        }
        if (looksLikeDelimitedTable(normalized)) {
            return TextBlockKind.TABLE;
        }
        if (BULLET_PREFIX.matcher(normalized).matches()) {
            return TextBlockKind.LIST_ITEM;
        }
        if (delimitedRow(normalized).isEmpty()
                && relativeLineHeight >= MINIMUM_HEADING_HEIGHT && normalized.length() <= 120
                && normalized.lines().count() == 1 && !SENTENCE_END.matcher(normalized).matches()) {
            return TextBlockKind.HEADING;
        }
        if (ORDERED_LIST_PREFIX.matcher(normalized).matches()) {
            return TextBlockKind.LIST_ITEM;
        }
        return TextBlockKind.PARAGRAPH;
    }

    private static boolean looksLikeDelimitedTable(String text) {
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() < 2) {
            return false;
        }
        Optional<DelimitedRow> first = delimitedRow(lines.get(0));
        return first.isPresent() && lines.stream().skip(1)
                .allMatch(line -> first.equals(delimitedRow(line)));
    }

    public static Optional<DelimitedRow> delimitedRow(String text) {
        String row = Objects.requireNonNull(text, "text").strip();
        if (row.isEmpty() || row.lines().count() != 1) {
            return Optional.empty();
        }
        long tabs = row.chars().filter(value -> value == '\t').count();
        long pipes = row.chars().filter(value -> value == '|').count();
        if (tabs > 0 && pipes > 0 || tabs == 0 && pipes == 0) {
            return Optional.empty();
        }
        char delimiter = tabs > 0 ? '\t' : '|';
        long separators = tabs > 0 ? tabs : pipes;
        return Optional.of(new DelimitedRow(delimiter, Math.toIntExact(separators + 1)));
    }

    public record DelimitedRow(char delimiter, int columns) {
        public DelimitedRow {
            if (columns < 2) {
                throw new IllegalArgumentException("a delimited row requires at least two columns");
            }
        }
    }
}
