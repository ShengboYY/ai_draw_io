package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/** Cipher output plus enough metadata to decrypt later without changing credential rows. */
@Data
@Builder
public class EncryptedModelCredentialSecret {

    private String ciphertext;
    private String encryptionProvider;
    private String encryptionKeyId;
    private String nonce;
}
