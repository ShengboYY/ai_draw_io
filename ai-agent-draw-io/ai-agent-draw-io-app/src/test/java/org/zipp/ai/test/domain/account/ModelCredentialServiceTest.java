package org.zipp.ai.test.domain.account;

import org.junit.Before;
import org.junit.Test;
import org.zipp.ai.domain.account.model.entity.ModelCredential;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.CreateModelCredentialCommand;
import org.zipp.ai.domain.account.model.valobj.EncryptedModelCredentialSecret;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSecret;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialStatus;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSummary;
import org.zipp.ai.domain.account.service.DefaultModelCredentialService;
import org.zipp.ai.domain.account.service.IModelCredentialSecretCipher;
import org.zipp.ai.domain.account.service.IModelCredentialStore;
import org.zipp.ai.domain.account.service.IUserAccountStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ModelCredentialServiceTest {

    private FakeUserAccountStore users;
    private FakeModelCredentialStore credentials;
    private RecordingCipher cipher;
    private DefaultModelCredentialService service;

    @Before
    public void setUp() {
        users = new FakeUserAccountStore();
        credentials = new FakeModelCredentialStore();
        cipher = new RecordingCipher();
        service = new DefaultModelCredentialService(
                users,
                credentials,
                cipher,
                Clock.fixed(Instant.parse("2026-07-02T10:00:00Z"), ZoneOffset.UTC));
        users.put(activeUser("usr_alice"));
        users.put(activeUser("usr_bob"));
    }

    @Test
    public void createEncryptsApiKeyAndListsMaskedCredential() {
        ModelCredentialSummary created = service.create(command("usr_alice", "sk-live-secret-1234"));

        ModelCredential stored = credentials.only();
        assertEquals(created.getId(), stored.getId());
        assertEquals("usr_alice", stored.getUserId());
        assertEquals("ENV_AES_GCM", stored.getEncryptionProvider());
        assertEquals("env-key-1", stored.getEncryptionKeyId());
        assertEquals("nonce-1", stored.getEncryptionNonce());
        assertEquals("1234", stored.getKeyLastFour());
        assertNotEquals("raw key must not be persisted", "sk-live-secret-1234", stored.getEncryptedApiKey());
        assertFalse("ciphertext must not contain plaintext", stored.getEncryptedApiKey().contains("sk-live-secret-1234"));
        assertEquals("sk-live-secret-1234", cipher.lastPlaintext);

        List<ModelCredentialSummary> list = service.list("usr_alice");
        assertEquals(1, list.size());
        ModelCredentialSummary summary = list.get(0);
        assertEquals("OpenAI production", summary.getDisplayName());
        assertEquals("****1234", summary.getMaskedApiKey());
        assertEquals("ENV_AES_GCM", summary.getEncryptionProvider());
        assertEquals("env-key-1", summary.getEncryptionKeyId());
    }

    @Test
    public void createRejectsUsersThatAreNotVerifiedActiveAccounts() {
        users.put(UserAccount.builder()
                .id("usr_pending")
                .status(AccountStatus.PENDING_VERIFICATION)
                .sessionVersion(0)
                .build());

        assertIllegalArgument(() -> service.create(command("usr_pending", "sk-pending-1234")));
        assertIllegalArgument(() -> service.create(command("usr_missing", "sk-missing-1234")));
        assertEquals(0, credentials.all.size());
    }

    @Test
    public void disableAndDeleteOnlyAffectTheCallingUsersCredentials() {
        ModelCredentialSummary alice = service.create(command("usr_alice", "sk-alice-1234"));
        ModelCredentialSummary bob = service.create(command("usr_bob", "sk-bob-9876"));

        assertFalse(service.disable("usr_alice", bob.getId()));
        assertEquals(ModelCredentialStatus.ACTIVE, credentials.byId.get(bob.getId()).getStatus());

        assertTrue(service.disable("usr_alice", alice.getId()));
        assertEquals(ModelCredentialStatus.DISABLED, credentials.byId.get(alice.getId()).getStatus());
        assertEquals(ModelCredentialStatus.DISABLED, service.list("usr_alice").get(0).getStatus());

        assertFalse(service.delete("usr_bob", alice.getId()));
        assertTrue(service.delete("usr_alice", alice.getId()));
        assertEquals(0, service.list("usr_alice").size());
        assertEquals(1, service.list("usr_bob").size());
    }

    @Test
    public void resolveForChatVerifiesOwnershipBeforeDecryptingTheCredential() {
        ModelCredentialSummary alice = service.create(command("usr_alice", "sk-alice-1234"));
        cipher.resetDecryptions();

        assertIllegalArgument(() -> service.resolveForChat("usr_bob", alice.getId()));
        assertEquals("cross-user lookup must not decrypt ciphertext", 0, cipher.decryptCalls);

        ModelCredentialSecret resolved = service.resolveForChat("usr_alice", alice.getId());

        assertEquals("https://api.openai.com/v1", resolved.getBaseUrl());
        assertEquals("/chat/completions", resolved.getCompletionPath());
        assertEquals("gpt-4o", resolved.getModel());
        assertEquals("decrypted-api-key", resolved.getApiKey());
        assertEquals(1, cipher.decryptCalls);
    }

    @Test
    public void createRejectsUnsafeNetworkTargets() {
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "http://api.openai.com/v1", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://localhost:11434", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://localhost.", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://127.0.0.1", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://127.1", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://2130706433", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://api.openai.com/../metadata", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://api.openai.com/%2e%2e/metadata", "/chat/completions")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://api.openai.com", "https://evil.example.com/v1")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://api.openai.com", "/../metadata")));
        assertIllegalArgument(() -> service.create(command("usr_alice", "sk-live-1234", "https://api.openai.com", "/%2e%2e/metadata")));
        assertEquals(0, credentials.all.size());
    }

    private CreateModelCredentialCommand command(String userId, String apiKey) {
        return command(userId, apiKey, "https://api.openai.com/v1", "/chat/completions");
    }

    private CreateModelCredentialCommand command(String userId, String apiKey, String baseUrl, String completionPath) {
        return CreateModelCredentialCommand.builder()
                .userId(userId)
                .provider("openai")
                .baseUrl(baseUrl)
                .model("gpt-4o")
                .completionPath(completionPath)
                .displayName("OpenAI production")
                .apiKey(apiKey)
                .build();
    }

    private UserAccount activeUser(String id) {
        return UserAccount.builder()
                .id(id)
                .status(AccountStatus.ACTIVE)
                .sessionVersion(0)
                .build();
    }

    private void assertIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static final class FakeUserAccountStore implements IUserAccountStore {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        void put(UserAccount user) {
            byId.put(user.getId(), user);
        }

        @Override
        public Optional<UserAccount> findByEmailNormalized(String emailNormalized) {
            return Optional.empty();
        }

        @Override
        public Optional<UserAccount> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<UserAccount> listAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public void insert(UserAccount account) {
            byId.put(account.getId(), account);
        }

        @Override
        public void markVerified(String userId, Instant verifiedAt) {
            UserAccount user = byId.get(userId);
            if (user != null) {
                user.setStatus(AccountStatus.ACTIVE);
                user.setVerifiedAt(verifiedAt);
            }
        }

        @Override
        public boolean updatePasswordHashAndIncrementSessionVersion(String userId, String passwordHash, Instant updatedAt) {
            return false;
        }

        @Override
        public boolean disableAndIncrementSessionVersion(String userId, Instant updatedAt) {
            return false;
        }
    }

    private static final class FakeModelCredentialStore implements IModelCredentialStore {
        private final List<ModelCredential> all = new ArrayList<>();
        private final ConcurrentHashMap<String, ModelCredential> byId = new ConcurrentHashMap<>();

        @Override
        public void insert(ModelCredential credential) {
            all.add(credential);
            byId.put(credential.getId(), credential);
        }

        @Override
        public List<ModelCredential> listByUserId(String userId) {
            return all.stream()
                    .filter(c -> c.getUserId().equals(userId))
                    .filter(c -> c.getDeletedAt() == null)
                    .toList();
        }

        @Override
        public Optional<ModelCredential> findByUserIdAndId(String userId, String credentialId) {
            ModelCredential credential = byId.get(credentialId);
            if (credential == null || !credential.getUserId().equals(userId) || credential.getDeletedAt() != null) {
                return Optional.empty();
            }
            return Optional.of(credential);
        }

        @Override
        public boolean disable(String userId, String credentialId, Instant disabledAt) {
            ModelCredential credential = byId.get(credentialId);
            if (credential == null || !credential.getUserId().equals(userId) || credential.getDeletedAt() != null) {
                return false;
            }
            credential.setStatus(ModelCredentialStatus.DISABLED);
            credential.setDisabledAt(disabledAt);
            credential.setUpdatedAt(disabledAt);
            return true;
        }

        @Override
        public boolean delete(String userId, String credentialId, Instant deletedAt) {
            ModelCredential credential = byId.get(credentialId);
            if (credential == null || !credential.getUserId().equals(userId) || credential.getDeletedAt() != null) {
                return false;
            }
            credential.setDeletedAt(deletedAt);
            credential.setUpdatedAt(deletedAt);
            return true;
        }

        ModelCredential only() {
            assertEquals(1, all.size());
            return all.get(0);
        }
    }

    private static final class RecordingCipher implements IModelCredentialSecretCipher {
        private String lastPlaintext;
        private int counter;
        private int decryptCalls;

        @Override
        public EncryptedModelCredentialSecret encrypt(String plaintext) {
            lastPlaintext = plaintext;
            counter++;
            return EncryptedModelCredentialSecret.builder()
                    .ciphertext("ciphertext-" + counter)
                    .encryptionProvider("ENV_AES_GCM")
                    .encryptionKeyId("env-key-1")
                    .nonce("nonce-" + counter)
                    .build();
        }

        @Override
        public String decrypt(EncryptedModelCredentialSecret secret) {
            decryptCalls++;
            return "decrypted-api-key";
        }

        private void resetDecryptions() {
            decryptCalls = 0;
        }
    }
}
