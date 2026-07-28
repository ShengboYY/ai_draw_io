package org.zipp.ai.application.turn.agent;

/** Opaque reference to one immutable attempt-scoped draft version. */
public record DraftRef(String value) {

    public DraftRef {
        value = value == null ? "" : value.trim();
        if (value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("draft ref is invalid");
        }
    }
}
