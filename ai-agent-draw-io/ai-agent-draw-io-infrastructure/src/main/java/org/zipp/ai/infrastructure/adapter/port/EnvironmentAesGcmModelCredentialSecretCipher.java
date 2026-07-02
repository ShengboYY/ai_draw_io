package org.zipp.ai.infrastructure.adapter.port;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.account.model.valobj.EncryptedModelCredentialSecret;
import org.zipp.ai.domain.account.service.IModelCredentialSecretCipher;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM implementation backed by a single environment-managed key. */
@Component
public class EnvironmentAesGcmModelCredentialSecretCipher implements IModelCredentialSecretCipher {

    public static final String PROVIDER = "ENV_AES_GCM";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final String rawKey;
    private final String keyId;
    private final SecureRandom secureRandom;

    @Autowired
    public EnvironmentAesGcmModelCredentialSecretCipher(
            @Value("${model-credential.encryption.key:}") String rawKey,
            @Value("${model-credential.encryption.key-id:env:MODEL_CREDENTIAL_ENCRYPTION_KEY}") String keyId) {
        this(rawKey, keyId, new SecureRandom());
    }

    EnvironmentAesGcmModelCredentialSecretCipher(String rawKey, String keyId, SecureRandom secureRandom) {
        this.rawKey = rawKey;
        this.keyId = keyId == null || keyId.isBlank() ? "env:MODEL_CREDENTIAL_ENCRYPTION_KEY" : keyId.trim();
        this.secureRandom = secureRandom == null ? new SecureRandom() : secureRandom;
    }

    @Override
    public EncryptedModelCredentialSecret encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is required.");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return EncryptedModelCredentialSecret.builder()
                    .ciphertext(Base64.getEncoder().encodeToString(ciphertext))
                    .encryptionProvider(PROVIDER)
                    .encryptionKeyId(keyId)
                    .nonce(Base64.getEncoder().encodeToString(nonce))
                    .build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt model credential.", e);
        }
    }

    @Override
    public String decrypt(EncryptedModelCredentialSecret secret) {
        if (secret == null) {
            throw new IllegalArgumentException("secret is required.");
        }
        if (!PROVIDER.equals(secret.getEncryptionProvider())) {
            throw new IllegalArgumentException("Unsupported credential encryption provider.");
        }
        if (!keyId.equals(secret.getEncryptionKeyId())) {
            throw new IllegalArgumentException("Credential encryption key id does not match this environment.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = Base64.getDecoder().decode(secret.getNonce());
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(secret.getCiphertext()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt model credential.", e);
        }
    }

    private SecretKeySpec keySpec() {
        byte[] key = decodeKey();
        return new SecretKeySpec(key, "AES");
    }

    private byte[] decodeKey() {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException("model-credential.encryption.key is required to save model credentials.");
        }
        String value = rawKey.trim();
        if (value.startsWith("base64:")) {
            return requireValidKeyLength(Base64.getDecoder().decode(value.substring("base64:".length())));
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (isValidKeyLength(decoded.length)) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Plain 16/24/32-byte keys are supported for local development and tests.
        }
        return requireValidKeyLength(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] requireValidKeyLength(byte[] key) {
        if (!isValidKeyLength(key.length)) {
            throw new IllegalStateException("model credential encryption key must be 16, 24, or 32 bytes.");
        }
        return key;
    }

    private boolean isValidKeyLength(int bytes) {
        return bytes == 16 || bytes == 24 || bytes == 32;
    }
}
