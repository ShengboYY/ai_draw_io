package org.zipp.ai.application.turn;

public record InstanceBootId(String value) {

    public InstanceBootId {
        ContractValues.requiredText(value, "value");
    }
}
