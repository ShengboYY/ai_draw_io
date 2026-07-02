package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.EncryptedModelCredentialSecret;

/** Encryption seam: environment-key AES-GCM today; AWS KMS can implement the same contract later. */
public interface IModelCredentialSecretCipher {

    EncryptedModelCredentialSecret encrypt(String plaintext);

    String decrypt(EncryptedModelCredentialSecret secret);
}
