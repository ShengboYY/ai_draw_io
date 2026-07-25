package org.zipp.ai.application.turn;

/** Small validation helpers shared by immutable application contracts. */
final class ContractValues {

    private ContractValues() {
    }

    static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
