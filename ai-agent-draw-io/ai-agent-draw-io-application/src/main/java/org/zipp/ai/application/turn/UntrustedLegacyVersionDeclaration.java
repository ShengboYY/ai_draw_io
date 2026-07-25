package org.zipp.ai.application.turn;

public record UntrustedLegacyVersionDeclaration(String value) {

    public UntrustedLegacyVersionDeclaration {
        ContractValues.requiredText(value, "value");
    }
}
