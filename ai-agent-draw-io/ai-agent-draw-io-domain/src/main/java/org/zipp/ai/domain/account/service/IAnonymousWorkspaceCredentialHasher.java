package org.zipp.ai.domain.account.service;

/** HMAC boundary for high-entropy anonymous workspace secrets. */
public interface IAnonymousWorkspaceCredentialHasher {

    String hash(String rawSecret);

    boolean matches(String rawSecret, String expectedHash);
}
