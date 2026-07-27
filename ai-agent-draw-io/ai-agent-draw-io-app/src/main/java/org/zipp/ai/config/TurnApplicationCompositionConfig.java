package org.zipp.ai.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.zipp.ai.application.turn.LegacyRetirementGatePort;
import org.zipp.ai.application.turn.MigrationModeSwitchOutcome;
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
import org.zipp.ai.application.turn.TurnEngineCohortSelector;
import org.zipp.ai.application.turn.TurnEngineMigrationStatePort;
import org.zipp.ai.application.turn.TurnEngineMigrationControlPort;
import org.zipp.ai.application.turn.TurnEngineMigrationCoordinator;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnStartCommitPort;
import org.zipp.ai.application.turn.TurnStatusQueryPort;
import org.zipp.ai.application.turn.ExplicitTurnCancellationPort;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnAttemptCancellationRegistry;
import org.zipp.ai.application.turn.TurnAttemptCancellationSignalPort;
import org.zipp.ai.application.turn.NoopTurnLifecycleTracePort;
import org.zipp.ai.application.turn.TurnLifecycleTracePort;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.trigger.http.turn.TurnHttpControlAdapter;
import org.zipp.ai.trigger.http.turn.TurnHttpDeliveryAdapter;
import org.zipp.ai.trigger.http.turn.TurnHttpRequestTranslator;

import java.util.Locale;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/** Bootstrap-only M1 composition; lifecycle startup is opt-in after the matching release manifest. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "turn-engine.lifecycle.enabled", havingValue = "true")
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
            AdmissionBarrier admissionBarrier,
            TurnEngineCohortSelector cohortSelector
    ) {
        return new TurnEngineAdmissionService(
                migrationState, assignments, admissionBarrier, cohortSelector);
    }

    @Bean
    public TurnEngineCohortSelector turnEngineCohortSelector(
            @Value("${turn-engine.canary.rollout-salt:m6-default}") String rolloutSalt,
            @Value("${turn-engine.canary.rollout-percent:0}") int rolloutPercent,
            @Value("${turn-engine.canary.allowlist:}") String allowlist
    ) {
        Set<String> allowlistedCohorts = Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new org.zipp.ai.application.turn.StableTurnEngineCohortSelector(
                rolloutSalt, rolloutPercent, allowlistedCohorts);
    }

    @Bean
    public TurnEngineMigrationCoordinator turnEngineMigrationCoordinator(
            AdmissionBarrier admissionBarrier,
            TurnEngineMigrationControlPort migrationControl,
            LegacyRetryExpiryPort expiry,
            ObjectProvider<LegacyRetirementGatePort> retirement
    ) {
        return new TurnEngineMigrationCoordinator(
                admissionBarrier,
                migrationControl,
                expiry,
                retirement.getIfAvailable(LegacyRetirementGatePort::unavailable));
    }

    @Bean
    public DiagramTurnFacade diagramTurnFacade(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnAdmissionProfilePort profile,
            TurnEngineAdmissionService admission,
            TurnStartCommitPort turnStart,
            TurnAdmissionGate admissionGate,
            ObjectProvider<TurnLifecycleTracePort> trace
    ) {
        return new DefaultDiagramTurnFacade(
                conversations, conversationResolver, profile, admission, turnStart, admissionGate,
                trace.getIfAvailable(() -> NoopTurnLifecycleTracePort.INSTANCE));
    }

    @Bean
    public TurnDeliveryExecutor turnDeliveryExecutor(
            DiagramTurnFacade facade,
            ObjectProvider<TurnAttemptExecutionRunner> runner
    ) {
        // Resolve lazily: accepted V2 work must not inherit a null runner captured during startup.
        return new DefaultTurnDeliveryExecutor(facade, runner::getIfAvailable);
    }

    @Bean
    public TurnHttpRequestTranslator turnHttpRequestTranslator() {
        return new TurnHttpRequestTranslator();
    }

    @Bean
    public TurnHttpDeliveryAdapter turnHttpDeliveryAdapter(
            TurnHttpRequestTranslator translator,
            TurnDeliveryExecutor executor
    ) {
        // The browser DTO is translated at the edge; product execution remains V2-only.
        return new TurnHttpDeliveryAdapter(translator, executor);
    }

    @Bean
    public TurnHttpControlAdapter turnHttpControlAdapter(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnControlFacade control
    ) {
        // Control requests share canonical identity and owner fencing without exposing takeover APIs.
        return new TurnHttpControlAdapter(conversations, conversationResolver, control);
    }

    @Bean
    public TurnAttemptCancellationRegistry turnAttemptCancellationRegistry() {
        return new TurnAttemptCancellationRegistry();
    }

    @Bean
    public TurnControlFacade turnControlFacade(
            TurnStatusQueryPort status,
            ExplicitTurnCancellationPort cancellation,
            TurnAttemptLeasePort leases,
            AttemptDeadlineCancellationPort deadlines,
            TurnAttemptTakeoverPort takeovers,
            AdmissionBarrier admissionBarrier,
            TurnAttemptCancellationSignalPort cancellationSignals,
            ObjectProvider<TurnLifecycleTracePort> trace
    ) {
        return new DefaultTurnControlFacade(
                status, cancellation, leases, deadlines, takeovers, admissionBarrier,
                cancellationSignals, trace.getIfAvailable(() -> NoopTurnLifecycleTracePort.INSTANCE));
    }

    @Bean
    public ApplicationRunner turnAdmissionStartup(
            TurnAdmissionGate admissionGate,
            TurnEngineMigrationStatePort migrationState,
            TurnEngineMigrationCoordinator migrationCoordinator,
            ObjectProvider<TurnAttemptExecutionRunner> runner,
            @Value("${turn-engine.migration.startup-target-mode:}") String startupTargetMode
    ) {
        InstanceBootId bootId = new InstanceBootId(UUID.randomUUID().toString());
        return args -> {
            // Keep HTTP admission closed until the singleton lock and orphan repair both succeed.
            InstanceLockOutcome outcome = admissionGate.start(bootId);
            if (outcome != InstanceLockOutcome.ACQUIRED) {
                throw new IllegalStateException("TURN_INSTANCE_NOT_READY");
            }
            if (startupTargetMode == null || startupTargetMode.isBlank()) {
                return;
            }
            TurnEngineMode targetMode;
            try {
                targetMode = TurnEngineMode.valueOf(startupTargetMode.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("TURN_MIGRATION_TARGET_MODE_INVALID", exception);
            }
            if (targetMode != TurnEngineMode.LEGACY && runner.getIfAvailable() == null) {
                // Fail startup before changing durable migration state; V2 may never accept
                // a turn unless the complete claim-to-terminal execution graph exists.
                throw new IllegalStateException("TURN_V2_EXECUTION_NOT_READY");
            }
            MigrationModeSwitchOutcome migration = migrationCoordinator.switchMode(
                    migrationState.current(), targetMode);
            if (migration instanceof MigrationModeSwitchOutcome.Rejected rejected) {
                // An explicitly requested migration must never leave a rejected startup half-open.
                throw new IllegalStateException("TURN_MIGRATION_STARTUP_REJECTED:" + rejected.code());
            }
        };
    }
}
