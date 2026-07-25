package org.zipp.ai.application.turn.checkpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical digest for the closed decision payload and its pinned inputs. */
public final class TurnDecisionCheckpointDigestCalculator {

    private TurnDecisionCheckpointDigestCalculator() {
    }

    public static String digestFor(
            int schemaVersion,
            String contextReadSetDigest,
            String inputBindingDigest,
            String decisionKind,
            String decisionJson
    ) {
        String canonical = field("schemaVersion", String.valueOf(schemaVersion))
                + field("contextReadSetDigest", contextReadSetDigest)
                + field("inputBindingDigest", inputBindingDigest)
                + field("decisionKind", decisionKind)
                + field("decisionJson", decisionJson);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String field(String name, String value) {
        return name + "=" + value.length() + ":" + value + "\n";
    }
}
