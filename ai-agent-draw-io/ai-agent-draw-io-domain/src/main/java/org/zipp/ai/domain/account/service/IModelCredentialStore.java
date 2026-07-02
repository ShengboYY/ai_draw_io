package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.entity.ModelCredential;

import java.time.Instant;
import java.util.List;

/** Persistence port for encrypted model credentials. Mutations include userId for ownership checks. */
public interface IModelCredentialStore {

    void insert(ModelCredential credential);

    List<ModelCredential> listByUserId(String userId);

    boolean disable(String userId, String credentialId, Instant disabledAt);

    boolean delete(String userId, String credentialId, Instant deletedAt);
}
