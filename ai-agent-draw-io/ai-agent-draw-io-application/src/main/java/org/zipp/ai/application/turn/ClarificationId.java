package org.zipp.ai.application.turn;

public record ClarificationId(String value) {

    public ClarificationId {
        ContractValues.requiredText(value, "value");
    }
}
