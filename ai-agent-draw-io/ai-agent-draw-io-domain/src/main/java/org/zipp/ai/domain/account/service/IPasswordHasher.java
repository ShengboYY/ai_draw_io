package org.zipp.ai.domain.account.service;

/**
 * Password hashing seam. Implemented with an adaptive hash (BCrypt) in the infrastructure layer so the
 * domain never depends on a specific crypto library and stays unit-testable with a fake.
 */
public interface IPasswordHasher {

    /** Hash a raw password for storage. Never returns the raw password. */
    String hash(String rawPassword);

    /** Verify a raw password against a stored hash. */
    boolean matches(String rawPassword, String passwordHash);

}
