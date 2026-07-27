package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Parses an explicit locale-rule action; ordinary chat never creates Memory. */
public record ExplicitMemoryDecision(
        String decisionKey,
        String applicabilityStage,
        String canonicalText,
        RememberDecisionDeclaration declaration
) {

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
