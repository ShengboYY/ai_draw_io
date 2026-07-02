package org.zipp.ai.infrastructure.adapter.port;

import org.springframework.stereotype.Component;
import org.zipp.ai.domain.account.service.ITokenHasher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic SHA-256 hasher for high-entropy verification/reset tokens.
 * <p>
 * A slow adaptive hash is not required here because the raw token is already 256 bits of entropy from
 * {@link SecureRandomTokenFactory}; SHA-256 keeps the lookup O(1) while still making the stored value
 * unusable if the database leaks.
 */
@Component
public class Sha256TokenHasher implements ITokenHasher {

    @Override
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", e);
        }
    }
}
