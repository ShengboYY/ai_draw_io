package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.IssuedAnonymousWorkspace;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;

import java.util.Optional;

/** Public domain seam for issuing, authenticating, and claiming anonymous ownership. */
public interface IAnonymousWorkspaceIdentityService {

    IssuedAnonymousWorkspace issue();

    Optional<ResolvedOwner> authenticate(String rawCredential);

    String claim(String rawCredential, String targetUserId);
}
