package org.zipp.ai.trigger.http.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/** Produces a rotating pseudonymous IP key; raw client addresses never enter persistence or logs. */
public final class DailyHmacRateKeyFactory {

    private final byte[] secret;
    private final Clock clock;

    public DailyHmacRateKeyFactory(String secret, Clock clock) {
        String value = Objects.requireNonNull(secret, "secret").trim();
        if (value.length() < 32) {
            throw new IllegalArgumentException("rate key secret must contain at least 32 characters");
        }
        this.secret = value.getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String create(String remoteAddress) {
        String address = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
        String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((day + ":" + address).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", e);
        }
    }
}
