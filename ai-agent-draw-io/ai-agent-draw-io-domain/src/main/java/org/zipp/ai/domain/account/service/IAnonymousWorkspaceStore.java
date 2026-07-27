package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.entity.AnonymousWorkspace;

import java.util.Optional;

/** Persistence boundary for the anonymous workspace aggregate. */
public interface IAnonymousWorkspaceStore {

    void insert(AnonymousWorkspace workspace);

    Optional<AnonymousWorkspace> findByCredentialId(String credentialId);

    boolean saveClaim(AnonymousWorkspace workspace);
}
