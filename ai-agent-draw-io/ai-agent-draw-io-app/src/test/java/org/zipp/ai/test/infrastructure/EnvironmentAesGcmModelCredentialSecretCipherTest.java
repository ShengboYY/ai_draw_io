package org.zipp.ai.test.infrastructure;

import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.EncryptedModelCredentialSecret;
import org.zipp.ai.infrastructure.adapter.port.EnvironmentAesGcmModelCredentialSecretCipher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class EnvironmentAesGcmModelCredentialSecretCipherTest {

    @Test
    public void encryptsWithAesGcmMetadataAndDecryptsServerSide() {
        EnvironmentAesGcmModelCredentialSecretCipher cipher =
                new EnvironmentAesGcmModelCredentialSecretCipher("0123456789abcdef0123456789abcdef", "env-key-1");

        EncryptedModelCredentialSecret first = cipher.encrypt("sk-live-secret-1234");
        EncryptedModelCredentialSecret second = cipher.encrypt("sk-live-secret-1234");

        assertEquals("ENV_AES_GCM", first.getEncryptionProvider());
        assertEquals("env-key-1", first.getEncryptionKeyId());
        assertNotNull(first.getNonce());
        assertFalse(first.getCiphertext().contains("sk-live-secret-1234"));
        assertNotEquals("AES-GCM must use a fresh nonce per encryption", first.getNonce(), second.getNonce());
        assertNotEquals(first.getCiphertext(), second.getCiphertext());
        assertEquals("sk-live-secret-1234", cipher.decrypt(first));
        assertEquals("sk-live-secret-1234", cipher.decrypt(second));
    }

    @Test
    public void acceptsBase64EncodedEnvironmentKeys() {
        EnvironmentAesGcmModelCredentialSecretCipher cipher =
                new EnvironmentAesGcmModelCredentialSecretCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "env-key-1");

        EncryptedModelCredentialSecret secret = cipher.encrypt("sk-base64-1234");

        assertEquals("sk-base64-1234", cipher.decrypt(secret));
    }
}
