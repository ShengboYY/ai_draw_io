package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;

import java.util.Optional;

public interface ICurrentOwnerResolver {

    Optional<ResolvedOwner> resolve(OwnerResolutionCommand command);

}
