package org.zipp.ai.application.turn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Server-owned identity for one canonical model input. Runtime ADK sessions are deliberately not
 * part of this value; a fresh session may rebuild the same input after restart or takeover.
 */
public record ModelInputBinding(
        int schemaVersion,
        TurnKey turnKey,
        String contextReadSetDigest,
        String inputDigest
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ModelInputBinding {
        if (schemaVersion < 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported model input binding schema");
        }
        if (schemaVersion == 0) {
            if (turnKey != null || contextReadSetDigest == null || !contextReadSetDigest.isEmpty()) {
                throw new IllegalArgumentException("unbound model input binding has server facts");
            }
        } else {
            if (turnKey == null || !hexDigest(contextReadSetDigest)) {
                throw new IllegalArgumentException("bound model input identity is invalid");
            }
        }
        if (!hexDigest(inputDigest)) {
            throw new IllegalArgumentException("model input digest must be SHA-256");
        }
    }

    /** Compatibility value for projection-only callers; adapters reject it at the model boundary. */
    public static ModelInputBinding unbound() {
        return new ModelInputBinding(0, null, "", "0".repeat(64));
    }

    public static ModelInputBinding bound(TurnKey key, String readSetDigest, String inputDigest) {
        return new ModelInputBinding(CURRENT_SCHEMA_VERSION, key, readSetDigest, inputDigest);
    }

    public boolean isBound() {
        return schemaVersion == CURRENT_SCHEMA_VERSION && turnKey != null;
    }

    /** Wraps a rendered projection with the identity that a fresh model session must receive. */
    public String envelope(String renderedInput) {
        if (!isBound()) {
            throw new IllegalStateException("model input binding is not server-bound");
        }
        if (renderedInput == null || renderedInput.isBlank()) {
            throw new IllegalArgumentException("rendered model input must not be blank");
        }
        StringBuilder envelope = new StringBuilder(renderedInput.length() + 512);
        field(envelope, "MODEL_INPUT_SCHEMA_VERSION", String.valueOf(schemaVersion));
        field(envelope, "TURN_KEY_OWNER", turnKey.ownerKey());
        field(envelope, "TURN_KEY_CONVERSATION", turnKey.canonicalConversationId());
        field(envelope, "TURN_KEY_TURN", turnKey.turnId());
        field(envelope, "CONTEXT_READ_SET_DIGEST", contextReadSetDigest);
        field(envelope, "MODEL_INPUT_DIGEST", inputDigest);
        field(envelope, "RENDERED_INPUT_DIGEST", digestOf(renderedInput));
        field(envelope, "CANONICAL_RENDERED_INPUT", renderedInput);
        return envelope.toString();
    }

    /** Stable digest helper shared by application projections and cache/envelope validation. */
    public static String digestOf(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            String value = values[index] == null ? "" : values[index];
            canonical.append(index).append('=').append(value.length()).append(':').append(value).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void field(StringBuilder target, String name, String value) {
        target.append('[').append(name).append(" length=").append(value.length()).append("]\n")
                .append(value).append('\n');
    }

    private static boolean hexDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(character ->
                (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
    }
}
