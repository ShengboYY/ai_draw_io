package org.zipp.ai.infrastructure.adapter.vector;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.retrieval.port.TenantKeyPort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Produces stable opaque tenant metadata without exposing Owner identity to Pinecone. */
public final class HmacTenantKeyAdapter implements TenantKeyPort {
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public HmacTenantKeyAdapter(String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("tenant HMAC secret is required");
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    @Override
    public String opaqueKey(OwnerType ownerType, String ownerKey) {
        if (ownerType == null || ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("Owner identity is required");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(
                    (ownerType.name() + ":" + ownerKey.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", e);
        }
    }
}
