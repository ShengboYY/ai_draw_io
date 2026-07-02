package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.CreateModelCredentialCommand;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSummary;

import java.util.List;

public interface IModelCredentialService {

    ModelCredentialSummary create(CreateModelCredentialCommand command);

    List<ModelCredentialSummary> list(String userId);

    boolean disable(String userId, String credentialId);

    boolean delete(String userId, String credentialId);
}
