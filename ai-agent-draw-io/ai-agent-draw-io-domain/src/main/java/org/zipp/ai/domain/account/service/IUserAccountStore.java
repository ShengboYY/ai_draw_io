package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.entity.UserAccount;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for {@link UserAccount}. Implemented in the infrastructure layer (MyBatis). Lookups
 * are by normalized email so uniqueness is case/whitespace insensitive.
 */
public interface IUserAccountStore {

    Optional<UserAccount> findByEmailNormalized(String emailNormalized);

    Optional<UserAccount> findById(String id);

    /** Insert a new user. */
    void insert(UserAccount account);

    /** Transition a pending user to ACTIVE and stamp verified_at. */
    void markVerified(String userId, Instant verifiedAt);

}
