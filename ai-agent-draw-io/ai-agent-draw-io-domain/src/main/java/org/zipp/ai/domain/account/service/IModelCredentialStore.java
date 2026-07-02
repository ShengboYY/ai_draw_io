package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.entity.ModelCredential;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for encrypted model credentials. Mutations include userId for ownership checks. */
public interface IModelCredentialStore {

    void insert(ModelCredential credential);

    List<ModelCredential> listByUserId(String userId);

    Optional<ModelCredential> findByUserIdAndId(String userId, String credentialId);

    boolean disable(String userId, String credentialId, Instant disabledAt);

    boolean delete(String userId, String credentialId, Instant deletedAt);
}
