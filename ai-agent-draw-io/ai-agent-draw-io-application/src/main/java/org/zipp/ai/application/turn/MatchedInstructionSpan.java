package org.zipp.ai.application.turn;

public record MatchedInstructionSpan(String value) {

    public MatchedInstructionSpan {
        ContractValues.requiredText(value, "value");
    }
}
