package org.zipp.ai.test.domain.account;

import org.junit.Before;
import org.junit.Test;
import org.zipp.ai.domain.account.model.entity.AccountToken;
import org.zipp.ai.domain.account.model.entity.ModelCredential;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountDeletionResult;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;
import org.zipp.ai.domain.account.service.DefaultAccountDeletionService;
import org.zipp.ai.domain.account.service.DeletedUserAnonymizer;
import org.zipp.ai.domain.account.service.IAccountTokenStore;
import org.zipp.ai.domain.account.service.IModelCredentialStore;
import org.zipp.ai.domain.account.service.IUserAccountStore;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccountDeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-03T10:00:00Z");

    private FakeUserAccountStore userStore;
    private FakeAccountTokenStore tokenStore;
    private FakeModelCredentialStore credentialStore;
    private FakeCanvasStateStore canvasStateStore;
    private FakeConversationStore conversationStore;
    private FakeDebugTraceStore debugTraceStore;
    private FakeUsageTelemetryStore usageTelemetryStore;
    private FakeAdminAuditLogStore auditLogStore;
    private DefaultAccountDeletionService service;

    @Before
    public void setUp() {
        userStore = new FakeUserAccountStore();
        tokenStore = new FakeAccountTokenStore();
        credentialStore = new FakeModelCredentialStore();
        canvasStateStore = new FakeCanvasStateStore();
        conversationStore = new FakeConversationStore();
        debugTraceStore = new FakeDebugTraceStore();
        usageTelemetryStore = new FakeUsageTelemetryStore();
        auditLogStore = new FakeAdminAuditLogStore();
        service = new DefaultAccountDeletionService(
                userStore,
                tokenStore,
                credentialStore,
                canvasStateStore,
                conversationStore,
                debugTraceStore,
                usageTelemetryStore,
                auditLogStore,
                Clock.fixed(NOW, ZoneOffset.UTC));

        userStore.insert(UserAccount.builder()
                .id("usr_alice")
                .email("alice@example.com")
                .emailNormalized("alice@example.com")
                .passwordHash("hash-of-password")
                .status(AccountStatus.ACTIVE)
                .sessionVersion(2)
                .createdAt(Instant.parse("2026-07-01T10:00:00Z"))
                .updatedAt(Instant.parse("2026-07-01T10:00:00Z"))
                .verifiedAt(Instant.parse("2026-07-01T10:00:00Z"))
                .build());
    }

    @Test
    public void deletionRedactsAccountAndFansOutToAllRetentionStores() {
        Optional<AccountDeletionResult> result = service.deleteAccount("usr_alice");

        String anonymizedUserId = DeletedUserAnonymizer.anonymizedUserId("usr_alice");
        assertTrue(result.isPresent());
        assertEquals(anonymizedUserId, result.get().getAnonymizedUserId());
        assertEquals(NOW, result.get().getDeletedAt());

        assertTrue(userStore.findById("usr_alice").isEmpty());
        UserAccount deleted = userStore.findById(anonymizedUserId).orElseThrow();
        assertEquals(AccountStatus.DELETED, deleted.getStatus());
        assertEquals("{deleted}", deleted.getPasswordHash());
        assertEquals(3, deleted.getSessionVersion());
        assertEquals(NOW, deleted.getDeletedAt());
        assertTrue(deleted.getEmail().endsWith("@deleted.local"));

        assertEquals("usr_alice", tokenStore.deletedUserId);
        assertEquals("usr_alice", credentialStore.deletedUserId);
        assertEquals(anonymizedUserId, credentialStore.anonymizedUserId);
        assertTrue(credentialStore.encryptedMaterialCleared);
        assertEquals("usr_alice", conversationStore.deletedUserId);
        assertEquals("usr_alice", canvasStateStore.deletedUserId);
        assertEquals(anonymizedUserId, canvasStateStore.anonymizedUserId);
        assertTrue(canvasStateStore.canvasContentCleared);
        assertEquals("usr_alice", debugTraceStore.deletedContentUserId);
        assertEquals(anonymizedUserId, debugTraceStore.anonymizedUserId);
        assertEquals(NOW, debugTraceStore.deletedAt);
        assertEquals("usr_alice", usageTelemetryStore.originalUserId);
        assertEquals(anonymizedUserId, usageTelemetryStore.anonymizedUserId);
        assertEquals("usr_alice", auditLogStore.redactedOriginalUserId);
        assertEquals(anonymizedUserId, auditLogStore.redactedUserId);
    }

    @Test
    public void deletionReturnsEmptyForMissingUsers() {
        Optional<AccountDeletionResult> result = service.deleteAccount("missing");

        assertTrue(result.isEmpty());
        assertEquals(null, tokenStore.deletedUserId);
    }

    private static final class FakeUserAccountStore implements IUserAccountStore {
        private final ConcurrentHashMap<String, UserAccount> byId = new ConcurrentHashMap<>();

        @Override public Optional<UserAccount> findByEmailNormalized(String emailNormalized) {
            return byId.values().stream()
                    .filter(user -> emailNormalized.equals(user.getEmailNormalized()))
                    .findFirst();
        }
        @Override public Optional<UserAccount> findById(String id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<UserAccount> listAll() { return new ArrayList<>(byId.values()); }
        @Override public void insert(UserAccount account) { byId.put(account.getId(), account); }
        @Override public void markVerified(String userId, Instant verifiedAt) {}
        @Override public boolean updatePasswordHashAndIncrementSessionVersion(String userId, String passwordHash, Instant updatedAt) { return false; }
        @Override public boolean disableAndIncrementSessionVersion(String userId, Instant updatedAt) { return false; }

        @Override
        public boolean deleteAndRedact(String userId, String anonymizedUserId, String deletedEmail, Instant deletedAt) {
            UserAccount user = byId.remove(userId);
            if (user == null || user.getStatus() == AccountStatus.DELETED) {
                return false;
            }
            user.setId(anonymizedUserId);
            user.setEmail(deletedEmail);
            user.setEmailNormalized(deletedEmail);
            user.setPasswordHash("{deleted}");
            user.setStatus(AccountStatus.DELETED);
            user.setSessionVersion(user.getSessionVersion() + 1);
            user.setUpdatedAt(deletedAt);
            user.setDeletedAt(deletedAt);
            byId.put(anonymizedUserId, user);
            return true;
        }
    }

    private static final class FakeAccountTokenStore implements IAccountTokenStore {
        private String deletedUserId;

        @Override public void insert(AccountToken token) {}
        @Override public Optional<AccountToken> findByHashAndPurpose(String tokenHash, TokenPurpose purpose) { return Optional.empty(); }
        @Override public boolean markUsed(String tokenId, Instant usedAt) { return false; }
        @Override public int deleteByUserId(String userId) {
            deletedUserId = userId;
            return 1;
        }
    }

    private static final class FakeModelCredentialStore implements IModelCredentialStore {
        private String deletedUserId;
        private String anonymizedUserId;
        private boolean encryptedMaterialCleared;

        @Override public void insert(ModelCredential credential) {}
        @Override public List<ModelCredential> listByUserId(String userId) { return List.of(); }
        @Override public Optional<ModelCredential> findByUserIdAndId(String userId, String credentialId) { return Optional.empty(); }
        @Override public boolean disable(String userId, String credentialId, Instant disabledAt) { return false; }
        @Override public boolean delete(String userId, String credentialId, Instant deletedAt) { return false; }
        @Override public int deleteAllForUser(String userId, String anonymizedUserId, Instant deletedAt) {
            this.deletedUserId = userId;
            this.anonymizedUserId = anonymizedUserId;
            this.encryptedMaterialCleared = true;
            return 2;
        }
    }

    private static final class FakeCanvasStateStore implements ICanvasStateStore {
        private String deletedUserId;
        private String anonymizedUserId;
        private boolean canvasContentCleared;

        @Override public org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState save(
                org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState state) { return state; }
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState> find(
                String userId, String diagramId) { return Optional.empty(); }
        @Override public int deleteUserData(String userId, String anonymizedUserId) {
            this.deletedUserId = userId;
            this.anonymizedUserId = anonymizedUserId;
            this.canvasContentCleared = true;
            return 2;
        }
    }

    private static final class FakeConversationStore implements IDiagramConversationStore {
        private String deletedUserId;

        @Override public int deleteUserMessages(String userId) {
            this.deletedUserId = userId;
            return 3;
        }
    }

    private static final class FakeDebugTraceStore implements IAgentDebugTraceStore {
        private String deletedContentUserId;
        private String anonymizedUserId;
        private Instant deletedAt;

        @Override public void insertControl(org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl control) {}
        @Override public List<org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceControl> listEnabledControls() { return List.of(); }
        @Override public void insertCapture(org.zipp.ai.domain.agent.model.valobj.debugtrace.DebugTraceCapture capture) {}
        @Override public int deleteExpiredContent(Instant now) { return 0; }
        @Override public int extendRunContentExpiry(String runId, Instant expiresAt) { return 0; }
        @Override public int deleteContentForUser(String userId, String anonymizedUserId, Instant deletedAt) {
            this.deletedContentUserId = userId;
            this.anonymizedUserId = anonymizedUserId;
            this.deletedAt = deletedAt;
            return 1;
        }
    }

    private static final class FakeUsageTelemetryStore implements IAgentUsageTelemetryStore {
        private String originalUserId;
        private String anonymizedUserId;

        @Override public void insertRun(org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry run) {}
        @Override public void completeRun(String runId, String status, String errorClass, Instant completedAt, long latencyMs) {}
        @Override public void insertStep(org.zipp.ai.domain.agent.model.valobj.usage.AgentRunStepTelemetry step) {}
        @Override public void insertLlmCall(org.zipp.ai.domain.agent.model.valobj.usage.LlmCallTelemetry call) {}
        @Override public void insertToolCall(org.zipp.ai.domain.agent.model.valobj.usage.ToolCallTelemetry call) {}
        @Override public org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary summarizeForUser(String userId) { return org.zipp.ai.domain.agent.model.valobj.usage.AgentUsageSummary.empty(); }
        @Override public org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary summarizeGlobal() { return org.zipp.ai.domain.agent.model.valobj.usage.AdminUsageSummary.empty(); }
        @Override public List<org.zipp.ai.domain.agent.model.valobj.usage.UsageDimensionSummary> summarizeByProviderModelCredentialSource() { return List.of(); }
        @Override public Optional<org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail> findRunDetail(String runId) { return Optional.empty(); }
        @Override public List<org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry> listTerminalRunsBetween(
                Instant completedFrom, Instant completedTo, int limit) { return List.of(); }
        @Override public int anonymizeUser(String userId, String anonymizedUserId) {
            this.originalUserId = userId;
            this.anonymizedUserId = anonymizedUserId;
            return 4;
        }
    }

    private static final class FakeAdminAuditLogStore implements IAdminAuditLogStore {
        private String redactedOriginalUserId;
        private String redactedUserId;

        @Override public void insert(AdminAuditLog log) {}
        @Override public List<AdminAuditLog> listRecent(int limit) { return List.of(); }
        @Override public int redactDeletedUser(String userId, String redactedUserId) {
            this.redactedOriginalUserId = userId;
            this.redactedUserId = redactedUserId;
            return 1;
        }
    }
}
