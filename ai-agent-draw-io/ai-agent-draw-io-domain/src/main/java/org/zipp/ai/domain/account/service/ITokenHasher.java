package org.zipp.ai.domain.account.service;

/**
 * Deterministic hash for high-entropy verification/reset tokens. Deterministic (unlike password
 * hashing) so a submitted token can be looked up by hash without ever storing the raw value.
 */
public interface ITokenHasher {

    String hash(String rawToken);

}
