package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.AccountDeletionResult;

import java.util.Optional;

public interface IAccountDeletionService {

    Optional<AccountDeletionResult> deleteAccount(String userId);
}
