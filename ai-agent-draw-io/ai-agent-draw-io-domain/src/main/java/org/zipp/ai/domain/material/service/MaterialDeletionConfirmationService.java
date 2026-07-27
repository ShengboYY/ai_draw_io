package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.valobj.MaterialDeletionImpact;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Issues a short-lived HMAC token bound to the exact deletion impact and material generation. */
public final class MaterialDeletionConfirmationService {
    private static final String HMAC = "HmacSHA256";
    private final byte[] secret;
    private final Clock clock;
    private final Duration validity;

    public MaterialDeletionConfirmationService(String secret, Clock clock, Duration validity) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("deletion confirmation secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.validity = Objects.requireNonNull(validity, "validity");
        if (validity.isZero() || validity.isNegative() || validity.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("deletion confirmation validity must be within 30 minutes");
        }
    }

    public Confirmation issue(MaterialDeletionImpact impact) {
        Instant expiresAt = clock.instant().plus(validity);
        String payload = payload(impact, expiresAt.getEpochSecond());
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Confirmation(encoded + "." + sign(payload), expiresAt);
    }

    public boolean verifies(String token, MaterialDeletionImpact impact) {
        if (token == null || token.isBlank()) return false;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) return false;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] facts = payload.split(":", -1);
            if (facts.length != 4) return false;
            long expires = Long.parseLong(facts[3]);
            String expectedPayload = payload(impact, expires);
            return MessageDigestSupport.constantTime(parts[1], sign(expectedPayload))
                    && payload.equals(expectedPayload) && clock.instant().getEpochSecond() <= expires;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String payload(MaterialDeletionImpact impact, long expiresAt) {
        return impact.materialId() + ":" + impact.lifecycleGeneration() + ":"
                + impact.fingerprint() + ":" + expiresAt;
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required by the JVM", e);
        }
    }

    public record Confirmation(String token, Instant expiresAt) { }

    private static final class MessageDigestSupport {
        private static boolean constantTime(String left, String right) {
            return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                    right.getBytes(StandardCharsets.UTF_8));
        }
    }
}
