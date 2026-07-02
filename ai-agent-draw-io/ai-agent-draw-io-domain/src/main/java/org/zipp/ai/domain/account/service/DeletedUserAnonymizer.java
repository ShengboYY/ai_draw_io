package org.zipp.ai.domain.account.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class DeletedUserAnonymizer {

    private static final String PURPOSE = "ai-draw-io-account-deletion:";

    private DeletedUserAnonymizer() {}

    public static String anonymizedUserId(String userId) {
        return "deleted_usr_" + sha256(PURPOSE + userId).substring(0, 48);
    }

    public static String deletedEmail(String anonymizedUserId) {
        return anonymizedUserId + "@deleted.local";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
