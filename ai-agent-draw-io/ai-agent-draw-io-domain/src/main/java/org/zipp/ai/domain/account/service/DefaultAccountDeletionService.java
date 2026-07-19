package org.zipp.ai.domain.account.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.AccountDeletionResult;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.IDiagramConversationStore;
import org.zipp.ai.domain.agent.service.debugtrace.IAgentDebugTraceStore;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;
import org.zipp.ai.domain.material.service.MaterialDeletionModule;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public class DefaultAccountDeletionService implements IAccountDeletionService {

    private final IUserAccountStore userAccountStore;
    private final IAccountTokenStore accountTokenStore;
    private final IModelCredentialStore modelCredentialStore;
    private final ICanvasStateStore canvasStateStore;
    private final IDiagramConversationStore diagramConversationStore;
    private final IAgentDebugTraceStore debugTraceStore;
    private final IAgentUsageTelemetryStore usageTelemetryStore;
    private final IAdminAuditLogStore adminAuditLogStore;
    private final MaterialDeletionModule materialDeletionModule;
    private final Clock clock;

    @Autowired
    public DefaultAccountDeletionService(IUserAccountStore userAccountStore,
                                         IAccountTokenStore accountTokenStore,
                                         IModelCredentialStore modelCredentialStore,
                                         ICanvasStateStore canvasStateStore,
                                         IDiagramConversationStore diagramConversationStore,
                                         IAgentDebugTraceStore debugTraceStore,
                                         IAgentUsageTelemetryStore usageTelemetryStore,
                                         IAdminAuditLogStore adminAuditLogStore,
                                         Optional<MaterialDeletionModule> materialDeletionModule) {
        this(userAccountStore, accountTokenStore, modelCredentialStore, canvasStateStore, diagramConversationStore,
                debugTraceStore, usageTelemetryStore, adminAuditLogStore,
                materialDeletionModule.orElse(MaterialDeletionModule.NO_OP), Clock.systemUTC());
    }

    public DefaultAccountDeletionService(IUserAccountStore userAccountStore,
                                         IAccountTokenStore accountTokenStore,
                                         IModelCredentialStore modelCredentialStore,
                                         ICanvasStateStore canvasStateStore,
                                         IDiagramConversationStore diagramConversationStore,
                                         IAgentDebugTraceStore debugTraceStore,
                                         IAgentUsageTelemetryStore usageTelemetryStore,
                                         IAdminAuditLogStore adminAuditLogStore) {
        this(userAccountStore, accountTokenStore, modelCredentialStore, canvasStateStore, diagramConversationStore,
                debugTraceStore, usageTelemetryStore, adminAuditLogStore, MaterialDeletionModule.NO_OP,
                Clock.systemUTC());
    }

    public DefaultAccountDeletionService(IUserAccountStore userAccountStore,
                                         IAccountTokenStore accountTokenStore,
                                         IModelCredentialStore modelCredentialStore,
                                         ICanvasStateStore canvasStateStore,
                                         IDiagramConversationStore diagramConversationStore,
                                         IAgentDebugTraceStore debugTraceStore,
                                         IAgentUsageTelemetryStore usageTelemetryStore,
                                         IAdminAuditLogStore adminAuditLogStore,
                                         Clock clock) {
        this(userAccountStore, accountTokenStore, modelCredentialStore, canvasStateStore, diagramConversationStore,
                debugTraceStore, usageTelemetryStore, adminAuditLogStore, MaterialDeletionModule.NO_OP, clock);
    }

    public DefaultAccountDeletionService(IUserAccountStore userAccountStore,
                                         IAccountTokenStore accountTokenStore,
                                         IModelCredentialStore modelCredentialStore,
                                         ICanvasStateStore canvasStateStore,
                                         IDiagramConversationStore diagramConversationStore,
                                         IAgentDebugTraceStore debugTraceStore,
                                         IAgentUsageTelemetryStore usageTelemetryStore,
                                         IAdminAuditLogStore adminAuditLogStore,
                                         MaterialDeletionModule materialDeletionModule,
                                         Clock clock) {
        this.userAccountStore = Objects.requireNonNull(userAccountStore, "userAccountStore");
        this.accountTokenStore = Objects.requireNonNull(accountTokenStore, "accountTokenStore");
        this.modelCredentialStore = Objects.requireNonNull(modelCredentialStore, "modelCredentialStore");
        this.canvasStateStore = Objects.requireNonNull(canvasStateStore, "canvasStateStore");
        this.diagramConversationStore = Objects.requireNonNull(diagramConversationStore, "diagramConversationStore");
        this.debugTraceStore = Objects.requireNonNull(debugTraceStore, "debugTraceStore");
        this.usageTelemetryStore = Objects.requireNonNull(usageTelemetryStore, "usageTelemetryStore");
        this.adminAuditLogStore = Objects.requireNonNull(adminAuditLogStore, "adminAuditLogStore");
        this.materialDeletionModule = Objects.requireNonNull(materialDeletionModule, "materialDeletionModule");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    @Transactional
    public Optional<AccountDeletionResult> deleteAccount(String userId) {
        String originalUserId = StringUtils.trimToNull(userId);
        if (originalUserId == null) {
            return Optional.empty();
        }
        Optional<UserAccount> user = userAccountStore.findById(originalUserId);
        if (user.isEmpty() || user.get().getStatus() == AccountStatus.DELETED) {
            return Optional.empty();
        }

        Instant deletedAt = clock.instant();
        String anonymizedUserId = DeletedUserAnonymizer.anonymizedUserId(originalUserId);
        String deletedEmail = DeletedUserAnonymizer.deletedEmail(anonymizedUserId);

        // Delete sensitive child records before replacing the account's primary identifier.
        accountTokenStore.deleteByUserId(originalUserId);
        modelCredentialStore.deleteAllForUser(originalUserId, anonymizedUserId, deletedAt);
        diagramConversationStore.deleteUserMessages(originalUserId);
        canvasStateStore.deleteUserData(originalUserId, anonymizedUserId);
        debugTraceStore.deleteContentForUser(originalUserId, anonymizedUserId, deletedAt);
        usageTelemetryStore.anonymizeUser(originalUserId, anonymizedUserId);
        adminAuditLogStore.redactDeletedUser(originalUserId, anonymizedUserId);
        materialDeletionModule.requestAccountDeletion(originalUserId, deletedAt);

        boolean deleted = userAccountStore.deleteAndRedact(originalUserId, anonymizedUserId, deletedEmail, deletedAt);
        if (!deleted) {
            return Optional.empty();
        }
        return Optional.of(AccountDeletionResult.builder()
                .anonymizedUserId(anonymizedUserId)
                .deletedAt(deletedAt)
                .build());
    }
}
