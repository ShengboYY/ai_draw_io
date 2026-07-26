package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the only explicit v1 Memory language action; ordinary chat never creates Memory. */
public record ExplicitMemoryDecision(
        String decisionKey,
        String applicabilityStage,
        String canonicalText,
        RememberDecisionDeclaration declaration
) {

    private static final List<PhraseRule> PHRASE_RULES = List.of(
            new PhraseRule("MEMORY_V1_EXPLICIT_ZH_20260726",
                    Pattern.compile("^(?:请\\s*)?记住这个决定(?:\\s*[:：]\\s*|\\s+)(.+)$")),
            new PhraseRule("MEMORY_V1_EXPLICIT_EN_20260726",
                    Pattern.compile("^remember this decision(?:\\s*:\\s*|\\s+)(.+)$",
                            Pattern.CASE_INSENSITIVE)));

    public ExplicitMemoryDecision {
        ContractValues.requiredText(decisionKey, "decisionKey");
        ContractValues.requiredText(applicabilityStage, "applicabilityStage");
        ContractValues.requiredText(canonicalText, "canonicalText");
        if (declaration == null) {
            throw new IllegalArgumentException("declaration must not be null");
        }
    }

    public static Optional<ExplicitMemoryDecision> fromUserContent(String content) {
        return fromUserContent(content, null);
    }

    /** Parses a versioned explicit phrase and binds it before the turn is admitted. */
    public static Optional<ExplicitMemoryDecision> fromUserContent(String content, String chartbookId) {
        String normalized = content == null ? "" : content.trim();
        if (chartbookId == null || chartbookId.isBlank()) return Optional.empty();
        for (PhraseRule rule : PHRASE_RULES) {
            Matcher matcher = rule.pattern().matcher(normalized);
            if (!matcher.matches()) continue;
            String text = matcher.group(1).trim();
            if (text.isEmpty()) return Optional.empty();
            String span = matcher.group(0).substring(0,
                    matcher.group(0).length() - matcher.group(1).length()).trim();
            return Optional.of(new ExplicitMemoryDecision(
                    "remembered-decision",
                    "all",
                    text,
                    new RememberDecisionDeclaration(
                            1,
                            new MemoryWriteRuleVersion(rule.version()),
                            new MatchedInstructionSpan(span),
                            new MemoryWriteSemanticDigest(digest(text)),
                            chartbookId)));
        }
        return Optional.empty();
    }

    public String declarationDigest() {
        return declaration.digest().value();
    }

    public String candidateId(TurnKey turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        return "memory-" + digest(turn.ownerKey() + "\u001f" + turn.canonicalConversationId()
                + "\u001f" + turn.turnId() + "\u001f" + declarationDigest()).substring(0, 40);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record PhraseRule(String version, Pattern pattern) {
    }
}
