package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for one-time {@link AccountToken}s. Lookups are by token hash so the raw token
 * never has to be stored. Expiry and single-use checks are enforced in the domain service, not here,
 * so the service can distinguish invalid / expired / reused outcomes.
 */
public interface IAccountTokenStore {

    void insert(AccountToken token);

    Optional<AccountToken> findByHashAndPurpose(String tokenHash, TokenPurpose purpose);

    /** Mark a token consumed. Returns true only if this call was the one that consumed it. */
    boolean markUsed(String tokenId, Instant usedAt);

}
