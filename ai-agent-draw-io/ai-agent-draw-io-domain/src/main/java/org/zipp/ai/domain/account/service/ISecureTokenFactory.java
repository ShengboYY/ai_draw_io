package org.zipp.ai.domain.account.service;

/** Produces cryptographically strong, URL-safe one-time tokens for verification/reset links. */
public interface ISecureTokenFactory {

    String newToken();

}
