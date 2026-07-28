package org.zipp.ai.application.turn.skill;

/** Immutable identity for one visible skill version; the body remains outside planning state. */
public record DiagramSkillBinding(
        String name,
        String diagramType,
        String contentDigest
) {

    public DiagramSkillBinding {
        name = requiredBounded(name, "name", 128);
        diagramType = requiredBounded(diagramType, "diagramType", 128);
        if (!hexDigest(contentDigest)) {
            throw new IllegalArgumentException("contentDigest must be SHA-256");
        }
    }

    private static String requiredBounded(String value, String field, int limit) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > limit
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static boolean hexDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
