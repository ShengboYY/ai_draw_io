package org.zipp.ai.domain.account.service;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.entity.AnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.AnonymousWorkspaceCredential;
import org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Domain service that keeps raw anonymous secrets outside persistence. */
@Service
public class DefaultAnonymousWorkspaceIdentityService implements IAnonymousWorkspaceIdentityService {

    private final IAnonymousWorkspaceStore workspaceStore;
    private final ISecureTokenFactory tokenFactory;
    private final IAnonymousWorkspaceCredentialHasher credentialHasher;

    public DefaultAnonymousWorkspaceIdentityService(IAnonymousWorkspaceStore workspaceStore,
                                                    ISecureTokenFactory tokenFactory,
                                                    IAnonymousWorkspaceCredentialHasher credentialHasher) {
        this.workspaceStore = workspaceStore;
        this.tokenFactory = tokenFactory;
        this.credentialHasher = credentialHasher;
    }

    @Override
    public IssuedAnonymousWorkspace issue() {
        String ownerId = "anon_" + UUID.randomUUID();
        String credentialId = "awc_" + UUID.randomUUID();
        AnonymousWorkspaceCredential credential = AnonymousWorkspaceCredential.issue(
                credentialId, tokenFactory.newToken());
        AnonymousWorkspace workspace = AnonymousWorkspace.issue(
                ownerId,
                credentialId,
                credentialHasher.hash(credential.getSecret()),
                Instant.now());
        workspaceStore.insert(workspace);
        return new IssuedAnonymousWorkspace(ownerId, credential.serialize());
    }

    @Override
    public Optional<ResolvedOwner> authenticate(String rawCredential) {
        Optional<AnonymousWorkspaceCredential> parsed = AnonymousWorkspaceCredential.parse(rawCredential);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        AnonymousWorkspaceCredential credential = parsed.get();
        return workspaceStore.findByCredentialId(credential.getCredentialId())
                .filter(AnonymousWorkspace::acceptsCredential)
                .filter(workspace -> credentialHasher.matches(
                        credential.getSecret(), workspace.getCredentialHash()))
                .map(workspace -> ResolvedOwner.anonymous(workspace.getOwnerId()));
    }

    @Override
    public String claim(String rawCredential, String targetUserId) {
        AnonymousWorkspaceCredential credential = AnonymousWorkspaceCredential.parse(rawCredential)
                .orElseThrow(() -> new IllegalArgumentException("Anonymous workspace credential is invalid"));
        AnonymousWorkspace workspace = workspaceStore.findByCredentialId(credential.getCredentialId())
                .filter(AnonymousWorkspace::acceptsCredential)
                .filter(candidate -> credentialHasher.matches(
                        credential.getSecret(), candidate.getCredentialHash()))
                .orElseThrow(() -> new IllegalArgumentException("Anonymous workspace credential is invalid"));
        workspace.claimBy(targetUserId, Instant.now());
        if (!workspaceStore.saveClaim(workspace)) {
            throw new IllegalStateException("Anonymous workspace claim was not persisted");
        }
        return workspace.getOwnerId();
    }
}
