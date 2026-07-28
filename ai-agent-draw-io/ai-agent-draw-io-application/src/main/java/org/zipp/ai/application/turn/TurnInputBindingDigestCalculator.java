package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Builds the immutable input binding stored with the first V2 claim. */
public final class TurnInputBindingDigestCalculator {

    private TurnInputBindingDigestCalculator() {
    }

    public static String current(UserTurnCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, "clientMessageId", command.clientMessageId());
        append(canonical, "content", command.content());
        append(canonical, "attachments", command.declarations().currentTurnAttachments().stream()
                .map(OpaqueConversationFileRef::value)
                .toList());
        append(canonical, "clarification", command.declarations().clarificationReply().toString());
        append(canonical, "legacySources", command.declarations().legacySelectedSources().stream()
                .map(UntrustedLegacyVersionDeclaration::value)
                .toList());
        append(canonical, "memory", command.declarations().memoryWrite().toString());
        if (!command.declarations().requestedDiagramSkills().isEmpty()) {
            // Keep legacy empty-skill bindings stable while binding every new explicit selection.
            append(canonical, "diagramSkills", command.declarations().requestedDiagramSkills().stream()
                    .map(RequestedDiagramSkill::value)
                    .toList());
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder canonical, String field, String value) {
        canonical.append(field).append('=').append(value.length()).append(':').append(value).append('\n');
    }

    private static void append(StringBuilder canonical, String field, java.util.List<String> values) {
        canonical.append(field).append('#').append(values.size()).append(':');
        for (String value : values) {
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        canonical.append('\n');
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
