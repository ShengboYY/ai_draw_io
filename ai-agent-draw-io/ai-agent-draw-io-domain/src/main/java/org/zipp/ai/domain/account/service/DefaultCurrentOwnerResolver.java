package org.zipp.ai.domain.account.service;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;

import java.util.Optional;

@Service
public class DefaultCurrentOwnerResolver implements ICurrentOwnerResolver {

    private final IAnonymousWorkspaceIdentityService anonymousWorkspaceIdentityService;

    public DefaultCurrentOwnerResolver(IAnonymousWorkspaceIdentityService anonymousWorkspaceIdentityService) {
        this.anonymousWorkspaceIdentityService = anonymousWorkspaceIdentityService;
    }

    @Override
    public Optional<ResolvedOwner> resolve(OwnerResolutionCommand command) {
        if (command != null) {
            String authenticatedUserId = trim(command.getAuthenticatedUserId());
            if (authenticatedUserId != null) {
                // Session identity always wins so that a stray legacy header cannot demote a real user.
                return Optional.of(ResolvedOwner.authenticated(authenticatedUserId));
            }
        }
        String credential = trim(command == null ? null : command.getAnonymousCredential());
        if (credential == null) {
            return Optional.empty();
        }
        // Authentication is delegated to the anonymous workspace domain service; owner ids alone
        // are never accepted as capabilities.
        return anonymousWorkspaceIdentityService.authenticate(credential);
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
