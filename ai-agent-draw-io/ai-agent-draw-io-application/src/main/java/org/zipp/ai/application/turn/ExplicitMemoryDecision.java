package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parses high-confidence explicit Memory intent before automatic post-turn extraction. */
public record ExplicitMemoryDecision(
        String decisionKey,
        String applicabilityStage,
        String canonicalText,
        RememberDecisionDeclaration declaration
) {
    private static final Pattern USER_SCOPE_MARKER = Pattern.compile(
            "(?:"
                    + "\\b(?:across|for|in)\\s+all\\s+(?:chartbooks?|projects?)\\b"
                    + "|(?:所有|全部|每个|每個|每一個)(?:画册|畫冊|图册|圖冊|项目|項目|專案)"
                    + "|(?:todos\\s+los|tous\\s+les|alle)\\s+(?:chartbooks?|proyectos|projets|projekte)"
                    + "|(?:すべての|全ての)(?:チャートブック|プロジェクト)"
                    + "|모든\\s*(?:차트북|프로젝트)"
                    + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
        if (chartbookId == null || chartbookId.isBlank()) {
            return Optional.empty();
        }
        return ExplicitMemoryLocaleRulePack.match(content).map(match -> {
            String decisionKey = "remembered-decision";
            String applicabilityStage = "all";
            RememberDecisionDeclaration declaration = new RememberDecisionDeclaration(
                    2,
                    new MemoryWriteRuleVersion(match.ruleVersion()),
                    new MatchedInstructionSpan(match.matchedSpan()),
                    new MemoryWriteSemanticDigest(digest(match.canonicalText())),
                    chartbookId,
                    decisionKey,
                    applicabilityStage,
                    match.canonicalText(),
                    match.locale());
            return new ExplicitMemoryDecision(
                    decisionKey, applicabilityStage, match.canonicalText(), declaration);
        });
    }

    /**
     * Replays the same deterministic locale rules over canonical committed content.
     *
     * <p>This is used only for an explicitly global instruction that cannot carry a legacy
     * Chartbook-bound declaration. Ordinary unbound instructions still go through inference.</p>
     */
    public static Optional<String> canonicalTextFromExplicitUserContent(String content) {
        return ExplicitMemoryLocaleRulePack.match(content)
                .map(ExplicitMemoryLocaleRulePack.Match::canonicalText);
    }

    /** Returns true only when the user names an all-Chartbook/project applicability scope. */
    public static boolean targetsUserScope(String content) {
        String normalized = content == null ? "" : content.trim();
        return !normalized.isEmpty() && USER_SCOPE_MARKER.matcher(normalized).find();
    }

    public String declarationDigest() {
        return declaration.digest().value();
    }

    public String candidateId(TurnKey turn) {
        return candidateId(turn, declarationDigest());
    }

    public static String candidateId(TurnKey turn, String declarationDigest) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        if (declarationDigest == null || declarationDigest.isBlank()) {
            throw new IllegalArgumentException("declarationDigest must not be blank");
        }
        return "memory-" + digest(turn.ownerKey() + "\u001f" + turn.canonicalConversationId()
                + "\u001f" + turn.turnId() + "\u001f" + declarationDigest).substring(0, 40);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
