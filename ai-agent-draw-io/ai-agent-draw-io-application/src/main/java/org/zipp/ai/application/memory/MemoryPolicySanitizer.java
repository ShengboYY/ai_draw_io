package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.MemoryWriteDeclaration;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Applies the v1 Memory boundary before any persistence call. Rejection values are fixed codes;
 * they never contain the rejected text or a digest of it.
 */
public final class MemoryPolicySanitizer {
    public static final String POLICY_VERSION = "MEMORY_V1_CONFIRMED_DECISION";
    public static final Duration DEFAULT_PENDING_TTL = Duration.ofHours(24);
    private static final int MAX_TEXT = 1_000;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_ -]?key|secret|password|passwd|private[_ -]?key|bearer\\s+[a-z0-9._-]+)");
    private static final Pattern EXTERNAL_FACT = Pattern.compile(
            "(?i)(https?://|www\\.|source\\s*[:=]|according to|citation|引用|来源)");
    private static final Pattern PII = Pattern.compile(
            "(?i)([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|\\+?[0-9][0-9 ()-]{7,}[0-9])");
    private static final Pattern PROFILE_FIELD = Pattern.compile(
            "(?i)\\b(instructions?|goal|summary|glossary|style|stable[_ -]?constraints?)\\b|\\b(指令|目标|摘要|术语|样式|约束)\\b");

    public SanitizationOutcome sanitize(MemoryProposalCommand command) {
        if (command == null) {
            return new SanitizationOutcome.Rejected("MEMORY_COMMAND_INVALID");
        }
        MemoryWriteDeclaration declaration = command.declaration();
        if (!(declaration instanceof RememberDecisionDeclaration remember)) {
            return new SanitizationOutcome.Rejected("MEMORY_EXPLICIT_CONFIRMATION_REQUIRED");
        }
        if (!remember.digest().value().equals(command.declarationDigest())) {
            return new SanitizationOutcome.Rejected("MEMORY_DECLARATION_DIGEST_CONFLICT");
        }
        if (!KEY.matcher(command.decisionKey()).matches()) {
            return new SanitizationOutcome.Rejected("MEMORY_DECISION_KEY_INVALID");
        }
        String stage = normalize(command.applicabilityStage());
        if (stage.length() > 64 || !KEY.matcher(stage.replace(' ', '-')).matches()) {
            return new SanitizationOutcome.Rejected("MEMORY_APPLICABILITY_INVALID");
        }
        TextSanitizationOutcome textOutcome = sanitizeText(command.canonicalText());
        if (textOutcome instanceof TextSanitizationOutcome.Rejected rejected) {
            return new SanitizationOutcome.Rejected(rejected.code());
        }
        String text = ((TextSanitizationOutcome.Accepted) textOutcome).text();
        return new SanitizationOutcome.Accepted(new SanitizedMemoryProposal(
                command.turn(), command.chartbookId(), command.diagramId(), command.candidateId(),
                command.declarationDigest(), command.decisionKey(), stage, "CHARTBOOK",
                text, POLICY_VERSION, DEFAULT_PENDING_TTL));
    }

    /** Applies the same content policy to an explicit user edit without creating a proposal. */
    public TextSanitizationOutcome sanitizeReplacement(String value) {
        return sanitizeText(value);
    }

    /**
     * Applies the v1 content boundary to an automatic shadow candidate. This method deliberately
     * does not accept a MemoryWriteDeclaration, so shadow output cannot become a v1 proposal.
     */
    public TextSanitizationOutcome sanitizeShadowText(String value) {
        return sanitizeText(value);
    }

    /** Profile owns these fields; automatic Memory must never claim them through a key. */
    public boolean isProfileOwnedField(String value) {
        return value != null && PROFILE_FIELD.matcher(value).find();
    }

    private TextSanitizationOutcome sanitizeText(String rawText) {
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

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public sealed interface SanitizationOutcome
            permits SanitizationOutcome.Accepted, SanitizationOutcome.Rejected {
        record Accepted(SanitizedMemoryProposal proposal) implements SanitizationOutcome {
        }

        record Rejected(String code) implements SanitizationOutcome {
            public Rejected {
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("code must not be blank");
                }
            }
        }
    }

    public sealed interface TextSanitizationOutcome
            permits TextSanitizationOutcome.Accepted, TextSanitizationOutcome.Rejected {
        record Accepted(String text) implements TextSanitizationOutcome {
        }

        record Rejected(String code) implements TextSanitizationOutcome {
        }
    }
}
