package org.zipp.ai.application.turn.planning;

final class PlanningContractValues {

    private PlanningContractValues() {
    }

    static String digest(String value, String field) {
        if (value == null || value.length() != 64
                || value.chars().anyMatch(character -> !isLowerHex(character))) {
            throw new IllegalArgumentException(field + " must be SHA-256 hex");
        }
        return value;
    }

    private static boolean isLowerHex(int character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f');
    }
}
