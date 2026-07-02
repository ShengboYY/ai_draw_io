package org.zipp.ai.infrastructure.adapter.port;

import org.springframework.stereotype.Component;
import org.zipp.ai.domain.account.service.IPasswordHasher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF-style adaptive password hasher.
 * <p>
 * We deliberately avoid pulling in spring-security-crypto here because {@code #2} scope only needs
 * one-way password verification: introducing the full security module without wiring in filters would
 * create dormant configuration surface. The implementation uses a per-password random salt with many
 * rounds of SHA-256, which is intentionally slow enough to resist offline guessing at rest. When
 * {@code #3} (Log In / Spring Security sessions) lands, this class is replaced by BCrypt behind the
 * same {@link IPasswordHasher} seam without touching the domain service or any callers.
 */
@Component
public class BcryptPasswordHasher implements IPasswordHasher {

    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final String PREFIX = "pbkdf1$sha256$";
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RNG.nextBytes(salt);
        String encoded = compute(rawPassword, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + encoded;
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        if (passwordHash == null || !passwordHash.startsWith(PREFIX)) {
            return false;
        }
        String[] parts = passwordHash.substring(PREFIX.length()).split("\\$");
        if (parts.length != 3) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            String expected = parts[2];
            return constantTimeEquals(expected, compute(rawPassword, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String compute(String rawPassword, byte[] salt, int iterations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = concat(salt, rawPassword.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < iterations; i++) {
                value = digest.digest(value);
            }
            return Base64.getEncoder().encodeToString(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", e);
        }
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
