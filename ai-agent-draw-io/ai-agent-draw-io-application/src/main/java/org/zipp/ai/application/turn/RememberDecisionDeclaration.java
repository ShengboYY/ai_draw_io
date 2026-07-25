package org.zipp.ai.application.turn;

public record RememberDecisionDeclaration(
        int schemaVersion,
        MemoryWriteRuleVersion ruleVersion,
        MatchedInstructionSpan matchedSpan,
        MemoryWriteSemanticDigest digest
) implements MemoryWriteDeclaration {

    public RememberDecisionDeclaration {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (ruleVersion == null || matchedSpan == null || digest == null) {
            throw new IllegalArgumentException("memory declaration values must not be null");
        }
    }
}
