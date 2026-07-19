package org.zipp.ai.infrastructure.adapter.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.account.service.IAnonymousWorkspaceCredentialHasher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMACs anonymous secrets with a server-only pepper. A plain digest would let a database reader
 * verify stolen browser credentials without also possessing application secrets.
 */
@Component
public class HmacAnonymousWorkspaceCredentialHasher implements IAnonymousWorkspaceCredentialHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_PEPPER_CHARACTERS = 32;

    private final byte[] pepper;

    public HmacAnonymousWorkspaceCredentialHasher(
            @Value("${app.security.anonymous-workspace-pepper:${ANONYMOUS_WORKSPACE_PEPPER:}}") String pepper) {
        this.pepper = pepper == null ? new byte[0] : pepper.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String hash(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalArgumentException("Anonymous workspace secret is required");
        }
        requireConfiguredPepper();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawSecret.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Anonymous workspace HMAC is unavailable", e);
        }
    }

    @Override
    public boolean matches(String rawSecret, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        byte[] actual = hash(rawSecret).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = expectedHash.getBytes(StandardCharsets.US_ASCII);
        // Constant-time comparison avoids revealing which prefix of a credential hash matched.
        return MessageDigest.isEqual(actual, expected);
    }

    private void requireConfiguredPepper() {
        if (pepper.length < MINIMUM_PEPPER_CHARACTERS) {
            throw new IllegalStateException(
                    "ANONYMOUS_WORKSPACE_PEPPER must contain at least 32 UTF-8 bytes");
        }
    }
}
