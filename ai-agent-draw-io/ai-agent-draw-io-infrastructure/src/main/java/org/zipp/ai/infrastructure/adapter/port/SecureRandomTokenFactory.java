package org.zipp.ai.infrastructure.adapter.port;

import org.springframework.stereotype.Component;
import org.zipp.ai.domain.account.service.ISecureTokenFactory;

import java.security.SecureRandom;
import java.util.Base64;

/** 32-byte URL-safe token backed by {@link SecureRandom}. */
@Component
public class SecureRandomTokenFactory implements ISecureTokenFactory {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    public String newToken() {
        byte[] buffer = new byte[TOKEN_BYTES];
        RNG.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
