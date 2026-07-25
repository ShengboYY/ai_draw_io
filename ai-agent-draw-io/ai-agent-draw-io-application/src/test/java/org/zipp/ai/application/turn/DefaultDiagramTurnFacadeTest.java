package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultDiagramTurnFacadeTest {

    @Test
    void canonicalReferenceResolvesBeforeAdmissionAndClaim() {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = activeConversation();
        UserTurnCommand command = command("conversation:conversation-1");
        AtomicReference<String> resolvedId = new AtomicReference<>();
        AtomicReference<TurnStartCommand> started = new AtomicReference<>();
        TurnEngineAssignment assignment = assignment();
        FencedAttempt attempt = new FencedAttempt(
                assignment.key(),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                4,
                "input",
                assignment.policy());

        ConversationCatalogPort catalog = new ConversationCatalogPort() {
            @Override
            public ConversationRef findOrCreateDefault(AuthenticatedActor ignored, String ignoredDiagram) {
                throw new AssertionError("default lookup must not be used");
            }

            @Override
            public ConversationRef requireActiveBinding(
                    AuthenticatedActor ignored, String conversationId, String ignoredDiagram) {
                resolvedId.set(conversationId);
                return conversation;
            }

            @Override
            public ConversationRef resolveLegacyAlias(
                    AuthenticatedActor ignored, String ignoredSession, String ignoredDiagram) {
                throw new AssertionError("legacy lookup must not be used");
            }
        };
        TurnEngineAdmissionService admission = new TurnEngineAdmissionService(
                () -> migration(),
                ignored -> new AdmissionWriteOutcome.Assigned(assignment),
                new OpenAdmissionBarrier());
        DefaultDiagramTurnFacade facade = new DefaultDiagramTurnFacade(
                catalog,
                new ConversationReferenceResolver(),
                new FixedProfile(),
                admission,
                commandToStart -> {
                    started.set(commandToStart);
                    return new TurnStartOutcome.Claimed(attempt, 42);
                },
                new OpenAdmissionBarrier());

        TurnSubmission submission = facade.execute(actor, command, ignored -> {
            throw new AssertionError("claim does not publish delivery events");
        });

        TurnSubmission.ExecutionAccepted accepted = assertInstanceOf(
                TurnSubmission.ExecutionAccepted.class, submission);
        assertEquals("conversation-1", resolvedId.get());
        assertEquals(assignment.key(), accepted.key());
        assertEquals(assignment.key(), started.get().key());
        assertEquals(command.content(), started.get().userMessage());
        assertEquals(TurnInputBindingDigestCalculator.current(command), started.get().inputBindingDigest());
        assertEquals(accepted.attempt().lease(), accepted.leaseTiming().lease());
    }

    @Test
    void pausedFacadeDoesNotResolveOrCreateConversation() {
        boolean[] catalogCalled = {false};
        ConversationCatalogPort catalog = new ConversationCatalogPort() {
            @Override
            public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
                catalogCalled[0] = true;
                throw new AssertionError("paused admission must not create a conversation");
            }

            @Override
            public ConversationRef requireActiveBinding(
                    AuthenticatedActor actor, String conversationId, String diagramId) {
                catalogCalled[0] = true;
                throw new AssertionError("paused admission must not resolve a conversation");
            }

            @Override
            public ConversationRef resolveLegacyAlias(
                    AuthenticatedActor actor, String legacySessionId, String diagramId) {
                catalogCalled[0] = true;
                throw new AssertionError("paused admission must not resolve an alias");
            }
        };
        DefaultDiagramTurnFacade facade = new DefaultDiagramTurnFacade(
                catalog,
                new ConversationReferenceResolver(),
                new FixedProfile(),
                new TurnEngineAdmissionService(
                        () -> migration(),
                        ignored -> {
                            throw new AssertionError("paused admission must not write assignment");
                        },
                        new ClosedAdmissionBarrier()),
                ignored -> {
                    throw new AssertionError("paused admission must not claim");
                },
                new ClosedAdmissionBarrier());

        TurnSubmission.NotReady notReady = assertInstanceOf(
                TurnSubmission.NotReady.class,
                facade.execute(
                        new AuthenticatedActor("owner-1", "cohort-1"),
                        command(ConversationReferenceResolver.DEFAULT_REFERENCE),
                        ignored -> {
                        }));

        assertEquals("TURN_INSTANCE_NOT_READY", notReady.code());
        assertFalse(catalogCalled[0]);
    }

    @Test
    void terminalStartUnavailableIsForwardedAsTypedSubmission() {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = activeConversation();
        TurnEngineAssignment assignment = assignment();
        TurnStatusView terminalStatus = new TurnStatusView(
                assignment.key(), TurnStatus.CANCELLED, null, 1, "CANCELLED", null,
                Instant.parse("2026-07-26T00:01:00Z"));
        DefaultDiagramTurnFacade facade = new DefaultDiagramTurnFacade(
                fixedCatalog(conversation),
                new ConversationReferenceResolver(),
                new FixedProfile(),
                new TurnEngineAdmissionService(
                        () -> migration(),
                        ignored -> new AdmissionWriteOutcome.Assigned(assignment),
                        new OpenAdmissionBarrier()),
                ignored -> new TurnStartOutcome.TerminalUnavailable(
                        terminalStatus, TerminalOutcomeDecoder.UNAVAILABLE_CODE),
                new OpenAdmissionBarrier());

        // An undecodable durable terminal must remain typed at the submission boundary.
        TurnSubmission.TerminalUnavailable unavailable = assertInstanceOf(
                TurnSubmission.TerminalUnavailable.class,
                facade.execute(actor, command("conversation:conversation-1"), ignored -> {
                }));

        assertEquals(assignment.key(), unavailable.key());
        assertEquals(terminalStatus, unavailable.status());
        assertEquals(TerminalOutcomeDecoder.UNAVAILABLE_CODE, unavailable.code());
    }

    @Test
    void resolverRejectsBareReferenceAndRoutesLegacyPrefixToAliasPort() {
        ConversationRef conversation = activeConversation();
        AtomicReference<String> resolvedAlias = new AtomicReference<>();
        ConversationCatalogPort catalog = new ConversationCatalogPort() {
            @Override
            public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
                return conversation;
            }

            @Override
            public ConversationRef requireActiveBinding(
                    AuthenticatedActor actor, String conversationId, String diagramId) {
                throw new AssertionError("canonical lookup must not be used");
            }

            @Override
            public ConversationRef resolveLegacyAlias(
                    AuthenticatedActor actor, String legacySessionId, String diagramId) {
                resolvedAlias.set(legacySessionId);
                return conversation;
            }
        };
        ConversationReferenceResolver resolver = new ConversationReferenceResolver();

        assertEquals(
                conversation,
                resolver.resolve(catalog,
                        new AuthenticatedActor("owner-1", "cohort-1"),
                        command("legacy:session-1")));
        assertEquals("session-1", resolvedAlias.get());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                catalog,
                new AuthenticatedActor("owner-1", "cohort-1"),
                command("session-1")));
    }

    @Test
    void concurrentSameTurnSubmissionsProduceOneClaimAndOneRunningReplay() throws Exception {
        AuthenticatedActor actor = new AuthenticatedActor("owner-1", "cohort-1");
        ConversationRef conversation = activeConversation();
        UserTurnCommand command = command("conversation:conversation-1");
        TurnEngineAssignment assignment = assignment();
        FencedAttempt attempt = new FencedAttempt(
                assignment.key(),
                new AttemptLease("attempt-1", 1, Instant.parse("2026-07-26T00:01:00Z"), 30_000),
                4,
                "input",
                assignment.policy());
        AtomicReference<Boolean> claimed = new AtomicReference<>(false);
        int[] startCalls = {0};
        TurnStatusView runningStatus = new TurnStatusView(
                assignment.key(), TurnStatus.RUNNING, "attempt-1", 1, null, null,
                Instant.parse("2026-07-26T00:01:00Z"));
        TurnStartCommitPort starts = ignored -> {
            synchronized (startCalls) {
                startCalls[0]++;
            }
            if (claimed.compareAndSet(false, true)) {
                return new TurnStartOutcome.Claimed(attempt, 42);
            }
            return new TurnStartOutcome.AlreadyRunning(runningStatus);
        };
        DefaultDiagramTurnFacade facade = new DefaultDiagramTurnFacade(
                fixedCatalog(conversation),
                new ConversationReferenceResolver(),
                new FixedProfile(),
                new TurnEngineAdmissionService(
                        () -> migration(),
                        ignored -> new AdmissionWriteOutcome.Assigned(assignment),
                        new OpenAdmissionBarrier()),
                starts,
                new OpenAdmissionBarrier());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TurnSubmission> first = executor.submit(() -> submitAfter(ready, release, facade, actor, command));
            Future<TurnSubmission> second = executor.submit(() -> submitAfter(ready, release, facade, actor, command));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent submissions did not reach the barrier");
            }
            release.countDown();

            List<TurnSubmission> submissions = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, submissions.stream()
                    .filter(TurnSubmission.ExecutionAccepted.class::isInstance).count());
            assertEquals(1, submissions.stream()
                    .filter(TurnSubmission.AlreadyRunning.class::isInstance).count());
            assertEquals(true, claimed.get());
            assertEquals(2, startCalls[0]);
        } finally {
            executor.shutdownNow();
        }
    }

    private static TurnSubmission submitAfter(
            CountDownLatch ready,
            CountDownLatch release,
            DefaultDiagramTurnFacade facade,
            AuthenticatedActor actor,
            UserTurnCommand command
    ) throws InterruptedException {
        ready.countDown();
        release.await(5, TimeUnit.SECONDS);
        return facade.execute(actor, command, ignored -> {
        });
    }

    private static ConversationCatalogPort fixedCatalog(ConversationRef conversation) {
        return new ConversationCatalogPort() {
            @Override
            public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
                return conversation;
            }

            @Override
            public ConversationRef requireActiveBinding(
                    AuthenticatedActor actor, String conversationId, String diagramId) {
                return conversation;
            }

            @Override
            public ConversationRef resolveLegacyAlias(
                    AuthenticatedActor actor, String legacySessionId, String diagramId) {
                return conversation;
            }
        };
    }

    private static UserTurnCommand command(String conversationReference) {
        return new UserTurnCommand(
                "turn-1",
                conversationReference,
                "diagram-1",
                "client-1",
                "draw a box",
                "runtime-1",
                new TurnDeclarations(
                        List.of(new OpaqueConversationFileRef("file-1")),
                        new NoClarificationReply(),
                        List.of(),
                        new NoMemoryWrite()));
    }

    private static ConversationRef activeConversation() {
        return new ConversationRef("conversation-1", "owner-1", "diagram-1", ConversationStatus.ACTIVE);
    }

    private static MigrationStateSnapshot migration() {
        return new MigrationStateSnapshot(
                1, TurnEngineMode.V2_CANARY, Instant.parse("2026-07-26T00:00:00Z"));
    }

    private static TurnEngineAssignment assignment() {
        return new TurnEngineAssignment(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                "diagram-1",
                new VersionedRequestFingerprint(1, "fingerprint"),
                SelectedTurnEngine.V2,
                migration(),
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"),
                new NoMemoryWrite());
    }

    private static final class FixedProfile implements TurnAdmissionProfilePort {
        @Override
        public VersionedRequestFingerprintSet fingerprints(UserTurnCommand command) {
            return new VersionedRequestFingerprintSet(
                    List.of(TurnRequestFingerprintCalculator.current(command)));
        }

        @Override
        public ExecutionPolicySnapshot policy(
                AuthenticatedActor actor, ConversationRef conversation, UserTurnCommand command) {
            return new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy");
        }
    }

    private static final class OpenAdmissionBarrier implements AdmissionBarrier {
        @Override
        public void pauseAndDrain() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static final class ClosedAdmissionBarrier implements AdmissionBarrier {
        @Override
        public void pauseAndDrain() {
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isOpen() {
            return false;
        }
    }
}
