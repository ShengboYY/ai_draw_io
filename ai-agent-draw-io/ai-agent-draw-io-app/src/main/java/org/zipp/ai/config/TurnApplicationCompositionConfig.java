package org.zipp.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zipp.ai.application.turn.AdmissionBarrier;
import org.zipp.ai.application.turn.ConfiguredTurnAdmissionProfile;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationReferenceResolver;
import org.zipp.ai.application.turn.DefaultDiagramTurnFacade;
import org.zipp.ai.application.turn.DiagramTurnFacade;
import org.zipp.ai.application.turn.InstanceBootId;
import org.zipp.ai.application.turn.InstanceLockOutcome;
import org.zipp.ai.application.turn.LegacyRetryExpiryPort;
import org.zipp.ai.application.turn.SingleActiveInstanceLock;
import org.zipp.ai.application.turn.StartupOrphanReconciler;
import org.zipp.ai.application.turn.TurnAdmissionGate;
import org.zipp.ai.application.turn.TurnAdmissionProfilePort;
import org.zipp.ai.application.turn.DefaultTurnControlFacade;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.DefaultTurnDeliveryExecutor;
import org.zipp.ai.application.turn.TurnDeliveryExecutor;
import org.zipp.ai.application.turn.TurnEngineAdmissionService;
import org.zipp.ai.application.turn.TurnEngineAssignmentPort;
import org.zipp.ai.application.turn.TurnEngineMigrationStatePort;
import org.zipp.ai.application.turn.TurnEngineMigrationControlPort;
import org.zipp.ai.application.turn.TurnEngineMigrationCoordinator;
import org.zipp.ai.application.turn.TurnStartCommitPort;
import org.zipp.ai.application.turn.TurnStatusQueryPort;
import org.zipp.ai.application.turn.ExplicitTurnCancellationPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;

import java.util.UUID;

/** Bootstrap-only composition for the M1 turn control plane; legacy HTTP remains unchanged. */
@Configuration(proxyBeanMethods = false)
public class TurnApplicationCompositionConfig {

    @Bean
    public ConversationReferenceResolver conversationReferenceResolver() {
        return new ConversationReferenceResolver();
    }

    @Bean
    public TurnAdmissionProfilePort turnAdmissionProfile(
            TurnEngineMigrationStatePort migrationState,
            @Value("${turn-engine.policy.schema-version:1}") int policySchemaVersion,
            @Value("${turn-engine.policy.snapshot-json:{\"policyVersion\":\"m1-default\"}}") String policySnapshotJson,
            @Value("${turn-engine.policy.hash:m1-default}") String policyHash
    ) {
        return new ConfiguredTurnAdmissionProfile(
                migrationState, policySchemaVersion, policySnapshotJson, policyHash);
    }

    @Bean
    public TurnAdmissionGate turnAdmissionGate(
            SingleActiveInstanceLock instanceLock,
            StartupOrphanReconciler orphanReconciler
    ) {
        return new TurnAdmissionGate(instanceLock, orphanReconciler);
    }

    @Bean
    public TurnEngineAdmissionService turnEngineAdmissionService(
            TurnEngineMigrationStatePort migrationState,
            TurnEngineAssignmentPort assignments,
            AdmissionBarrier admissionBarrier
    ) {
        return new TurnEngineAdmissionService(migrationState, assignments, admissionBarrier);
    }

    @Bean
    public TurnEngineMigrationCoordinator turnEngineMigrationCoordinator(
            AdmissionBarrier admissionBarrier,
            TurnEngineMigrationControlPort migrationControl,
            LegacyRetryExpiryPort expiry
    ) {
        return new TurnEngineMigrationCoordinator(admissionBarrier, migrationControl, expiry);
    }

    @Bean
    public DiagramTurnFacade diagramTurnFacade(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnAdmissionProfilePort profile,
            TurnEngineAdmissionService admission,
            TurnStartCommitPort turnStart,
            TurnAdmissionGate admissionGate
    ) {
        return new DefaultDiagramTurnFacade(
                conversations, conversationResolver, profile, admission, turnStart, admissionGate);
    }

    @Bean
    public TurnDeliveryExecutor turnDeliveryExecutor(DiagramTurnFacade facade) {
        return new DefaultTurnDeliveryExecutor(facade);
    }

    @Bean
    public TurnControlFacade turnControlFacade(
            TurnStatusQueryPort status,
            ExplicitTurnCancellationPort cancellation,
            TurnAttemptLeasePort leases,
            AttemptDeadlineCancellationPort deadlines,
            TurnAttemptTakeoverPort takeovers
    ) {
        return new DefaultTurnControlFacade(status, cancellation, leases, deadlines, takeovers);
    }

    @Bean
    public ApplicationRunner turnAdmissionStartup(TurnAdmissionGate admissionGate) {
        InstanceBootId bootId = new InstanceBootId(UUID.randomUUID().toString());
        return args -> {
            // Keep HTTP admission closed until the singleton lock and orphan repair both succeed.
            InstanceLockOutcome outcome = admissionGate.start(bootId);
            if (outcome != InstanceLockOutcome.ACQUIRED) {
                throw new IllegalStateException("TURN_INSTANCE_NOT_READY");
            }
        };
    }
}
