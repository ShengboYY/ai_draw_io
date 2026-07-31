package org.zipp.ai.application.memory;

import java.util.regex.Pattern;

/**
 * Shared content boundary for automatic extraction and user edits. Rejection values are fixed
 * codes; they never contain rejected text or a digest of it.
 */
public final class MemoryPolicySanitizer {
    private static final int MAX_TEXT = 1_000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_ -]?key|secret|password|passwd|private[_ -]?key|bearer\\s+[a-z0-9._-]+)");
    private static final Pattern EXTERNAL_FACT = Pattern.compile(
            "(?i)(https?://|www\\.|source\\s*[:=]|according to|citation|引用|来源)");
    private static final Pattern PII = Pattern.compile(
            "(?i)([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|\\+?[0-9][0-9 ()-]{7,}[0-9])");
    private static final Pattern PROFILE_FIELD = Pattern.compile(
            "(?i)\\b(instructions?|goal|summary|glossary|style|stable[_ -]?constraints?)\\b|\\b(指令|目标|摘要|术语|样式|约束)\\b");

    /** Profile owns these fields; automatic Memory must never claim them through a key. */
    public boolean isProfileOwnedField(String value) {
        return value != null && PROFILE_FIELD.matcher(value).find();
    }

    public TextSanitizationOutcome sanitizeText(String rawText) {
        String text = normalizeText(rawText);
        if (text.isBlank()) {
            return new TextSanitizationOutcome.Rejected("MEMORY_TEXT_EMPTY");
        }
        if (text.length() > MAX_TEXT) {
            return new TextSanitizationOutcome.Rejected("MEMORY_TEXT_TOO_LARGE");
        }
        if (SECRET.matcher(text).find()) {
            return new TextSanitizationOutcome.Rejected("MEMORY_SECRET_FORBIDDEN");
        }
        if (PII.matcher(text).find()) {
            return new TextSanitizationOutcome.Rejected("MEMORY_PII_FORBIDDEN");
        }
        if (EXTERNAL_FACT.matcher(text).find()) {
            return new TextSanitizationOutcome.Rejected("MEMORY_EXTERNAL_FACT_FORBIDDEN");
        }
        if (PROFILE_FIELD.matcher(text).find()) {
            return new TextSanitizationOutcome.Rejected("MEMORY_PROFILE_FIELD_FORBIDDEN");
        }
        return new TextSanitizationOutcome.Accepted(text);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public sealed interface TextSanitizationOutcome
            permits TextSanitizationOutcome.Accepted, TextSanitizationOutcome.Rejected {
        record Accepted(String text) implements TextSanitizationOutcome {
        }

        record Rejected(String code) implements TextSanitizationOutcome {
        }
    }
}
