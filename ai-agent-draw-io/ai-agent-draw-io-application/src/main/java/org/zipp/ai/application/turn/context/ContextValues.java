package org.zipp.ai.application.turn.context;

/** Context-local validation helpers keep the existing turn package surface unchanged. */
final class ContextValues {

    private ContextValues() {
    }

    static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
