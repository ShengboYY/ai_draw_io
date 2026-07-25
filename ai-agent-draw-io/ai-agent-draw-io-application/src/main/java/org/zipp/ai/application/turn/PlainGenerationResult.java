package org.zipp.ai.application.turn;

/** Model output passed to the fenced Plain commit adapter. */
public record PlainGenerationResult(
        String payloadRef,
        String canvasXml,
        String assistantMessage
) {

    public PlainGenerationResult {
        ContractValues.requiredText(payloadRef, "payloadRef");
        ContractValues.requiredText(canvasXml, "canvasXml");
        ContractValues.requiredText(assistantMessage, "assistantMessage");
    }
}
