package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Builds the v1 idempotency fingerprint from stable turn declarations only. */
public final class TurnRequestFingerprintCalculator {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private TurnRequestFingerprintCalculator() {
    }

    public static VersionedRequestFingerprint current(UserTurnCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, "diagramId", command.diagramId());
        append(canonical, "clientMessageId", command.clientMessageId());
        append(canonical, "content", command.content());
        append(canonical, "attachments", command.declarations().currentTurnAttachments().stream()
                .map(OpaqueConversationFileRef::value)
                .toList());
        append(canonical, "clarification", clarificationValue(command.declarations().clarificationReply()));
        append(canonical, "legacySources", command.declarations().legacySelectedSources().stream()
                .map(UntrustedLegacyVersionDeclaration::value)
                .toList());
        append(canonical, "memory", memoryValue(command.declarations().memoryWrite()));
        return new VersionedRequestFingerprint(CURRENT_SCHEMA_VERSION, sha256(canonical.toString()));
    }

    private static String clarificationValue(ClarificationReplyDeclaration declaration) {
        if (declaration instanceof NoClarificationReply) {
            return "NONE";
        }
        return "REPLY:" + ((ReplyToClarification) declaration).clarificationId().value();
    }

    private static String memoryValue(MemoryWriteDeclaration declaration) {
        if (declaration instanceof NoMemoryWrite) {
            return "NONE";
        }
        RememberDecisionDeclaration remember = (RememberDecisionDeclaration) declaration;
        return remember.schemaVersion() + "|"
                + remember.ruleVersion().value() + "|"
                + remember.matchedSpan().value() + "|"
                + remember.digest().value() + "|"
                + remember.chartbookId();
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
