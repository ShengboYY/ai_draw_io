package org.zipp.ai.application.turn;

/** Model output reference passed unchanged to the fenced Plain commit adapter. */
public record PlainGenerationResult(String payloadRef) {

    public PlainGenerationResult {
        ContractValues.requiredText(payloadRef, "payloadRef");
    }
}
