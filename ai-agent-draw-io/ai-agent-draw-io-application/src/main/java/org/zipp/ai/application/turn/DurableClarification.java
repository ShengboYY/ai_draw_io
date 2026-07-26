package org.zipp.ai.application.turn;

import org.zipp.ai.application.turn.planning.DirectCandidateFact;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable option authority persisted atomically with a needs-user-input terminal. */
public record DurableClarification(
        ClarificationId clarificationId,
        String safeMessage,
        String optionSetDigest,
        Duration validity,
        List<Option> options
) {

    public DurableClarification {
        if (clarificationId == null || validity == null
                || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException(
                    "clarification identity and positive validity are required");
        }
        ContractValues.requiredText(safeMessage, "safeMessage");
        SourceCommitBindingDigest.required(optionSetDigest, "optionSetDigest");
        options = List.copyOf(options == null ? List.of() : options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("clarification options must not be empty");
        }
        Set<String> optionIds = new HashSet<>();
        for (Option option : options) {
            if (!optionIds.add(option.optionId())) {
                throw new IllegalArgumentException("duplicate clarification option id");
            }
        }
    }

    public record Option(
            String optionId,
            String safeLabel,
            DirectCandidateFact candidate
    ) {
        public Option {
            ContractValues.requiredText(optionId, "optionId");
            ContractValues.requiredText(safeLabel, "safeLabel");
            if (candidate == null) {
                throw new IllegalArgumentException("candidate must not be null");
            }
        }
    }
}
