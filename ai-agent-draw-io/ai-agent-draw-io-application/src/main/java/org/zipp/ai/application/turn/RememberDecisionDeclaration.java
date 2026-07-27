package org.zipp.ai.application.turn;

public record RememberDecisionDeclaration(
        int schemaVersion,
        MemoryWriteRuleVersion ruleVersion,
        MatchedInstructionSpan matchedSpan,
        MemoryWriteSemanticDigest digest,
        String chartbookId,
        String decisionKey,
        String applicabilityStage,
        String canonicalText,
        String locale
) implements MemoryWriteDeclaration {

    public RememberDecisionDeclaration {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (ruleVersion == null || matchedSpan == null || digest == null
                || chartbookId == null || chartbookId.isBlank()) {
            throw new IllegalArgumentException("memory declaration values must not be null");
        }
        if (schemaVersion >= 2 && (blank(decisionKey) || blank(applicabilityStage)
                || blank(canonicalText) || blank(locale))) {
            throw new IllegalArgumentException("schema v2 memory proposal values must not be blank");
        }
    }

    /** Compatibility constructor for schema-v1 persisted declarations. */
    public RememberDecisionDeclaration(
            int schemaVersion,
            MemoryWriteRuleVersion ruleVersion,
            MatchedInstructionSpan matchedSpan,
            MemoryWriteSemanticDigest digest,
            String chartbookId
    ) {
        this(schemaVersion, ruleVersion, matchedSpan, digest, chartbookId,
                null, null, null, null);
    }

    public boolean hasPinnedProposal() {
        return schemaVersion >= 2 && !blank(decisionKey) && !blank(applicabilityStage)
                && !blank(canonicalText) && !blank(locale);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
