package org.zipp.ai.application.turn;

public record MemoryWriteRuleVersion(String value) {

    public MemoryWriteRuleVersion {
        ContractValues.requiredText(value, "value");
    }
}
