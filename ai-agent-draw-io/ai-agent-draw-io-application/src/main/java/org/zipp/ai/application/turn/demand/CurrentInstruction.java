package org.zipp.ai.application.turn.demand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Current user instruction; source demand evidence may only point into this value. */
public record CurrentInstruction(String value) {

    public CurrentInstruction {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public String digest() {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String spanDigest(int startInclusive, int endExclusive) {
        if (startInclusive < 0 || endExclusive > value.length() || startInclusive >= endExclusive) {
            throw new IllegalArgumentException("instruction span is out of bounds");
        }
        return new CurrentInstruction(value.substring(startInclusive, endExclusive)).digest();
    }
}
