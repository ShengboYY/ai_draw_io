package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.AdmissionWriteOutcome;
import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationStatus;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.ExplicitTurnCancellationPort;
import org.zipp.ai.application.turn.InstanceBootId;
import org.zipp.ai.application.turn.InstanceLockOutcome;
import org.zipp.ai.application.turn.LegacyRetryExpiryPort;
import org.zipp.ai.application.turn.MigrationModeSwitchCommand;
import org.zipp.ai.application.turn.MigrationModeSwitchOutcome;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.SingleActiveInstanceLock;
import org.zipp.ai.application.turn.StartupOrphanReconciler;
import org.zipp.ai.application.turn.TurnEngineAssignmentCommand;
import org.zipp.ai.application.turn.TurnEngineAssignmentPort;
import org.zipp.ai.application.turn.TurnEngineMigrationControlPort;
import org.zipp.ai.application.turn.TurnEngineMigrationStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQueryPort;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.application.turn.TurnStartCommand;
import org.zipp.ai.application.turn.TurnStartCommitPort;
import org.zipp.ai.application.turn.TurnStartOutcome;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.execution.TurnAttemptCompletion;
import org.zipp.ai.application.turn.execution.TurnAttemptExecutionRunner;
import org.zipp.ai.application.turn.execution.TurnAttemptLeaseSupervisor;
import org.zipp.ai.application.turn.execution.TurnV2TurnExecutor;
import org.zipp.ai.trigger.http.turn.TurnHttpControlAdapter;
import org.zipp.ai.trigger.http.turn.TurnHttpDeliveryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class TurnApplicationCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnApplicationCompositionConfig.class)
            .withPropertyValues("turn-engine.lifecycle.enabled=true")
            .withBean(SingleActiveInstanceLock.class, FakeInstanceLock::new)
            .withBean(StartupOrphanReconciler.class, FakeOrphanReconciler::new)
            .withBean(TurnEngineMigrationStatePort.class, FakeMigrationState::new)
            .withBean(TurnEngineMigrationControlPort.class, FakeMigrationControl::new)
            .withBean(LegacyRetryExpiryPort.class, FakeExpiry::new)
            .withBean(TurnStatusQueryPort.class, TurnApplicationCompositionConfigTest::fakeStatus)
            .withBean(ExplicitTurnCancellationPort.class, TurnApplicationCompositionConfigTest::fakeCancellation)
            .withBean(TurnAttemptLeasePort.class, TurnApplicationCompositionConfigTest::fakeLease)
            .withBean(AttemptDeadlineCancellationPort.class, TurnApplicationCompositionConfigTest::fakeDeadline)
            .withBean(TurnAttemptTakeoverPort.class, TurnApplicationCompositionConfigTest::fakeTakeover)
            .withBean(TurnEngineAssignmentPort.class, FakeAssignments::new)
            .withBean(ConversationCatalogPort.class, FakeConversationCatalog::new)
            .withBean(TurnStartCommitPort.class, FakeTurnStart::new);

    @Test
    void keepsDurableM1StartupDisabledUntilTheMigrationReleaseIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(org.zipp.ai.config.TurnApplicationCompositionConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            org.zipp.ai.application.turn.TurnAdmissionGate.class);
                    assertThat(context).doesNotHaveBean(org.springframework.boot.ApplicationRunner.class);
                });
    }

    @Test
    void composesFacadeAndOpensAdmissionOnlyAfterStartupRepair() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.DiagramTurnFacade.class);
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnAdmissionGate.class);
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnAttemptCancellationRegistry.class);
            assertThat(context).hasSingleBean(TurnHttpDeliveryAdapter.class);
            assertThat(context).hasSingleBean(TurnHttpControlAdapter.class);
            assertThat(context.getBean(org.zipp.ai.application.turn.TurnAdmissionGate.class).isOpen()).isFalse();

            org.springframework.boot.ApplicationRunner runner = context.getBean(org.springframework.boot.ApplicationRunner.class);
            runner.run(new org.springframework.boot.DefaultApplicationArguments());

            FakeInstanceLock lock = (FakeInstanceLock) context.getBean(SingleActiveInstanceLock.class);
            FakeOrphanReconciler reconciler = (FakeOrphanReconciler) context.getBean(StartupOrphanReconciler.class);
            assertThat(lock.acquired).isTrue();
            assertThat(reconciler.calls).isEqualTo(1);
            assertThat(context.getBean(org.zipp.ai.application.turn.TurnAdmissionGate.class).isOpen()).isTrue();
        });
    }

    @Test
    void doesNotRunMigrationWithoutAnExplicitStartupTarget() {
        contextRunner.run(context -> {
            context.getBean(org.springframework.boot.ApplicationRunner.class)
                    .run(new org.springframework.boot.DefaultApplicationArguments());

            FakeMigrationControl migration = context.getBean(FakeMigrationControl.class);
            assertThat(migration.commands).isEmpty();
        });
    }

    @Test
    void runsExplicitStartupMigrationOnlyAfterAdmissionStartup() {
        contextRunner.withPropertyValues("turn-engine.migration.startup-target-mode=ALL_V2")
                .run(context -> {
                    context.getBean(org.springframework.boot.ApplicationRunner.class)
                            .run(new org.springframework.boot.DefaultApplicationArguments());

                    FakeMigrationControl migration = context.getBean(FakeMigrationControl.class);
                    assertThat(migration.commands).singleElement()
                            .extracting(MigrationModeSwitchCommand::targetMode)
                            .isEqualTo(TurnEngineMode.ALL_V2);
                });
    }

    @Test
    void composesV2DeliveryHandoffWhenAnAttemptRunnerIsAvailable() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            contextRunner.withBean(
                            TurnAttemptExecutionRunner.class,
                            () -> v2Runner(scheduler))
                    .run(context -> {
                        assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnDeliveryExecutor.class);
                    });
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static TurnAttemptExecutionRunner v2Runner(ScheduledExecutorService scheduler) {
        TurnV2TurnExecutor executor = new TurnV2TurnExecutor() {
            @Override
            public TurnAttemptCompletion execute(
                    org.zipp.ai.application.turn.TurnSubmission.ExecutionAccepted accepted,
                    UserTurnCommand command,
                    TurnEventSink events
            ) {
                return new TurnAttemptCompletion.AttemptSelfAborted(
                        new org.zipp.ai.application.turn.TurnStatusRef(accepted.key()), "TEST_ONLY");
            }

            @Override
            public void disableWritesAndDrain(org.zipp.ai.application.turn.FencedAttempt attempt) {
                // The composition contract does not execute the supplied runner.
            }
        };
        TurnAttemptLeaseSupervisor heartbeat = new TurnAttemptLeaseSupervisor(
                ignored -> new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(30)),
                executor);
        return new TurnAttemptExecutionRunner(executor, heartbeat, Runnable::run, scheduler);
    }

    private static final class FakeInstanceLock implements SingleActiveInstanceLock {
        private boolean acquired;

        @Override
        public InstanceLockOutcome acquire(InstanceBootId bootId) {
            acquired = true;
            return InstanceLockOutcome.ACQUIRED;
        }

        @Override
        public boolean isHeld(InstanceBootId bootId) {
            return acquired;
        }
    }

    private static final class FakeOrphanReconciler implements StartupOrphanReconciler {
        private int calls;

        @Override
        public int reconcile(InstanceBootId currentBootId) {
            calls++;
            return 0;
        }
    }

    private static final class FakeMigrationState implements TurnEngineMigrationStatePort {
        @Override
        public MigrationStateSnapshot current() {
            return new MigrationStateSnapshot(0, TurnEngineMode.LEGACY, Instant.parse("2026-07-25T00:00:00Z"));
        }
    }

    private static final class FakeAssignments implements TurnEngineAssignmentPort {
        @Override
        public AdmissionWriteOutcome assignOrReuse(TurnEngineAssignmentCommand command) {
            return new AdmissionWriteOutcome.Rejected(command.key(), "TEST_ONLY");
        }
    }

    private static final class FakeMigrationControl implements TurnEngineMigrationControlPort {
        private final List<MigrationModeSwitchCommand> commands = new ArrayList<>();

        @Override
        public MigrationModeSwitchOutcome switchMode(MigrationModeSwitchCommand command) {
            commands.add(command);
            return new MigrationModeSwitchOutcome.Changed(new MigrationStateSnapshot(
                    command.expectedGeneration() + 1,
                    command.targetMode(),
                    Instant.parse("2026-07-26T00:00:00Z")));
        }
    }

    private static final class FakeExpiry implements LegacyRetryExpiryPort {
        @Override
        public int backfillRetryable(int batchSize) {
            return 0;
        }

        @Override
        public int expireDue(int batchSize) {
            return 0;
        }
    }

    private static TurnStatusQueryPort fakeStatus() {
        return (actor, query) -> new TurnStatusQueryOutcome.Available(
                new TurnStatusView(query.key(), TurnStatus.RUNNING, "attempt-1", 1,
                        null, null, java.time.Instant.parse("2026-07-26T00:00:00Z")));
    }

    private static ExplicitTurnCancellationPort fakeCancellation() {
        return (actor, command) -> new CancelTurnOutcome.Rejected("TEST_ONLY");
    }

    private static TurnAttemptLeasePort fakeLease() {
        return attempt -> new TurnAttemptLeasePort.LeaseTransientFailure(java.time.Duration.ofSeconds(1));
    }

    private static AttemptDeadlineCancellationPort fakeDeadline() {
        return (attempt, reason) -> new DeadlineCancelOutcome.TransientFailure("TEST_ONLY");
    }

    private static TurnAttemptTakeoverPort fakeTakeover() {
        return key -> new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
    }

    private static final class FakeConversationCatalog implements ConversationCatalogPort {
        @Override
        public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
            return active(actor, diagramId);
        }

        @Override
        public ConversationRef requireActiveBinding(AuthenticatedActor actor, String conversationId, String diagramId) {
            return active(actor, diagramId);
        }

        @Override
        public ConversationRef resolveLegacyAlias(AuthenticatedActor actor, String legacySessionId, String diagramId) {
            return active(actor, diagramId);
        }

        private ConversationRef active(AuthenticatedActor actor, String diagramId) {
            return new ConversationRef("conversation-test", actor.ownerKey(), diagramId, ConversationStatus.ACTIVE);
        }
    }

    private static final class FakeTurnStart implements TurnStartCommitPort {
        @Override
        public TurnStartOutcome start(TurnStartCommand command) {
            throw new UnsupportedOperationException("not used by composition test");
        }
    }
}
