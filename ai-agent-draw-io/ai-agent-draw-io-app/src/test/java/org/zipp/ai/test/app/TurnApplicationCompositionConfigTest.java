package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.zipp.ai.application.turn.AdmissionWriteOutcome;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationStatus;
import org.zipp.ai.application.turn.InstanceBootId;
import org.zipp.ai.application.turn.InstanceLockOutcome;
import org.zipp.ai.application.turn.MigrationModeSwitchOutcome;
import org.zipp.ai.application.turn.MigrationStateSnapshot;
import org.zipp.ai.application.turn.SingleActiveInstanceLock;
import org.zipp.ai.application.turn.StartupOrphanReconciler;
import org.zipp.ai.application.turn.TurnEngineAssignmentCommand;
import org.zipp.ai.application.turn.TurnEngineAssignmentPort;
import org.zipp.ai.application.turn.TurnEngineMigrationControlPort;
import org.zipp.ai.application.turn.TurnEngineMigrationStatePort;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnStartCommand;
import org.zipp.ai.application.turn.TurnStartCommitPort;
import org.zipp.ai.application.turn.TurnStartOutcome;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TurnApplicationCompositionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(org.zipp.ai.config.TurnApplicationCompositionConfig.class)
            .withBean(SingleActiveInstanceLock.class, FakeInstanceLock::new)
            .withBean(StartupOrphanReconciler.class, FakeOrphanReconciler::new)
            .withBean(TurnEngineMigrationStatePort.class, FakeMigrationState::new)
            .withBean(TurnEngineMigrationControlPort.class, FakeMigrationControl::new)
            .withBean(TurnEngineAssignmentPort.class, FakeAssignments::new)
            .withBean(ConversationCatalogPort.class, FakeConversationCatalog::new)
            .withBean(TurnStartCommitPort.class, FakeTurnStart::new);

    @Test
    void composesFacadeAndOpensAdmissionOnlyAfterStartupRepair() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.DiagramTurnFacade.class);
            assertThat(context).hasSingleBean(org.zipp.ai.application.turn.TurnAdmissionGate.class);
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
        @Override
        public MigrationModeSwitchOutcome switchMode(
                TurnEngineMode expectedMode,
                TurnEngineMode targetMode
        ) {
            return new MigrationModeSwitchOutcome.Rejected("TEST_ONLY");
        }
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
