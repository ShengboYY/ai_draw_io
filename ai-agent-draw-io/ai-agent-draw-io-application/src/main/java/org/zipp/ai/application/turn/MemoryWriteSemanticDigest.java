package org.zipp.ai.application.turn;

public record MemoryWriteSemanticDigest(String value) {

    public MemoryWriteSemanticDigest {
        ContractValues.requiredText(value, "value");
    }
}
