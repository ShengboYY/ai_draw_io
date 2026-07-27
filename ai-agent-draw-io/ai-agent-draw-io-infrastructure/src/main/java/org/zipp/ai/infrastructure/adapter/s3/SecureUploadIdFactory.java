package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.service.UploadIdFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public final class SecureUploadIdFactory implements UploadIdFactory {

    private final byte[] ownerPathSecret;

    public SecureUploadIdFactory(String ownerPathSecret) {
        String secret = Objects.requireNonNull(ownerPathSecret, "ownerPathSecret").trim();
        if (secret.length() < 32) {
            throw new IllegalArgumentException("owner path secret must contain at least 32 characters");
        }
        this.ownerPathSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override public String nextUploadId() { return "upl_" + UUID.randomUUID(); }
    @Override public String nextObjectId() { return "obj_" + UUID.randomUUID(); }
    @Override public String nextJobId() { return "job_" + UUID.randomUUID(); }

    @Override
    public String ownerPathToken(String ownerKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ownerPathSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(requireText(ownerKey).getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", e);
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ownerKey is required");
        }
        return value.trim();
    }
}
