package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Parses the only explicit v1 Memory language action; ordinary chat never creates Memory. */
public record ExplicitMemoryDecision(
        String decisionKey,
        String applicabilityStage,
        String canonicalText,
        RememberDecisionDeclaration declaration
) {

    private static final String PREFIX = "记住这个决定：";

    public ExplicitMemoryDecision {
        ContractValues.requiredText(decisionKey, "decisionKey");
        ContractValues.requiredText(applicabilityStage, "applicabilityStage");
        ContractValues.requiredText(canonicalText, "canonicalText");
        if (declaration == null) {
            throw new IllegalArgumentException("declaration must not be null");
        }
    }

    public static Optional<ExplicitMemoryDecision> fromUserContent(String content) {
        String normalized = content == null ? "" : content.trim();
        if (!normalized.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String text = normalized.substring(PREFIX.length()).trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String digest = digest(text);
        return Optional.of(new ExplicitMemoryDecision(
                "remembered-decision",
                "all",
                text,
                new RememberDecisionDeclaration(
                        1,
                        new MemoryWriteRuleVersion("MEMORY_V1_EXPLICIT_LANGUAGE"),
                        new MatchedInstructionSpan(PREFIX),
                        new MemoryWriteSemanticDigest(digest))));
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
}
