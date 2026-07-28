package org.zipp.ai.application.turn;

/** Untrusted caller declaration that is admitted only after catalog whitelist validation. */
public record RequestedDiagramSkill(String value) {

    private static final int MAX_LENGTH = 128;

    public RequestedDiagramSkill {
        value = value == null ? "" : value.trim();
        if (value.isBlank() || value.length() > MAX_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("requested diagram skill is invalid");
        }
    }
}
