package org.zipp.ai.application.turn.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical digest for the exact read-set identity persisted with a turn. */
public final class ContextReadSetDigestCalculator {

    private ContextReadSetDigestCalculator() {
    }

    public static String digestFor(
            int schemaVersion,
            long messageHighWater,
            ContextSlicePin summary,
            ContextSlicePin membership,
            ContextSlicePin profile,
            ContextSlicePin memory
    ) {
        String canonical = field("schemaVersion", String.valueOf(schemaVersion))
                + field("messageHighWater", String.valueOf(messageHighWater))
                + pin("summary", summary)
                + pin("membership", membership)
                + pin("profile", profile)
                + pin("memory", memory);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String pin(String name, ContextSlicePin pin) {
        return field(name + ".slice", pin.slice().name())
                + field(name + ".state", pin.state().name())
                + field(name + ".reference", pin.reference())
                + field(name + ".version", String.valueOf(pin.version()))
                + field(name + ".contentDigest", pin.contentDigest());
    }

    private static String field(String name, String value) {
        return name + "=" + value.length() + ":" + value + "\n";
    }
}
