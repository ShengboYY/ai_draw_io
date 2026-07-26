package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.zipp.ai.application.turn.AttemptDeadlineCancellationPort;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnCommand;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
import org.zipp.ai.application.turn.ConversationReferenceResolver;
import org.zipp.ai.application.turn.ConversationStatus;
import org.zipp.ai.application.turn.DeadlineCancelOutcome;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnAttemptLeasePort;
import org.zipp.ai.application.turn.TurnAttemptTakeoverPort;
import org.zipp.ai.application.turn.TurnControlFacade;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.TurnStatus;
import org.zipp.ai.application.turn.TurnStatusQuery;
import org.zipp.ai.application.turn.TurnStatusQueryOutcome;
import org.zipp.ai.application.turn.TurnStatusView;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TurnV2HttpControllerTest {

    @Test
    void submitAndControlEndpointsUseAuthenticatedOwnerAndMappedStatuses() {
        AtomicReference<AuthenticatedActor> submittedActor = new AtomicReference<>();
        TurnHttpDeliveryAdapter delivery = new TurnHttpDeliveryAdapter(
                new TurnHttpRequestTranslator(),
                (actor, command, events) -> {
                    submittedActor.set(actor);
                    return new org.zipp.ai.application.turn.TurnSubmission.NotReady(
                            "TURN_INSTANCE_NOT_READY");
                });
        TurnHttpControlAdapter control = new TurnHttpControlAdapter(
                new FakeConversationCatalog(), new ConversationReferenceResolver(), new FakeControl());
        TurnV2HttpController controller = new TurnV2HttpController(
                new FakeOwnerResolver(), delivery, control);

        ResponseEntity<TurnHttpDeliveryResult> submitted = controller.submit(new TurnHttpRequest(
                "turn-1", "conversation:conversation-1", "diagram-1", "client-1", "draw it",
                null, java.util.List.of(), null, java.util.List.of()));
        ResponseEntity<TurnHttpStatusResult> status = controller.status(
                "turn-1", "conversation:conversation-1", "diagram-1");
        ResponseEntity<TurnHttpCancelResult> cancelled = controller.cancel(
                "turn-1", "conversation:conversation-1", "diagram-1",
                new TurnHttpCancelBody("user_requested"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, submitted.getStatusCode());
        assertEquals(HttpStatus.OK, status.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, cancelled.getStatusCode());
        assertEquals("owner-1", submittedActor.get().ownerKey());
        assertEquals("owner-1", submittedActor.get().cohortKey());
    }

    @Test
    void controllerRequiresAnExplicitV2FeatureFlag() {
        ConditionalOnProperty condition = TurnV2HttpController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertNotNull(condition);
        assertEquals("turn-engine.http.v2.enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    static class FakeOwnerResolver extends CurrentOwnerHttpResolver {
        @Override
        public Optional<ResolvedOwner> resolve(String ignored) {
            return Optional.of(ResolvedOwner.authenticated("owner-1"));
        }
    }

    private static final class FakeConversationCatalog implements ConversationCatalogPort {
        @Override
        public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
            return conversation(actor, diagramId);
        }

        @Override
        public ConversationRef requireActiveBinding(
                AuthenticatedActor actor, String conversationId, String diagramId
        ) {
            return conversation(actor, diagramId);
        }

        @Override
        public ConversationRef resolveLegacyAlias(
                AuthenticatedActor actor, String legacySessionId, String diagramId
        ) {
            return conversation(actor, diagramId);
        }

        private ConversationRef conversation(AuthenticatedActor actor, String diagramId) {
            return new ConversationRef("conversation-1", actor.ownerKey(), diagramId, ConversationStatus.ACTIVE);
        }
    }

    private static final class FakeControl implements TurnControlFacade {
        @Override
        public TurnStatusQueryOutcome status(AuthenticatedActor actor, TurnStatusQuery query) {
            return new TurnStatusQueryOutcome.Available(new TurnStatusView(
                    query.key(), TurnStatus.RUNNING, "attempt-1", 1, null, null,
                    Instant.parse("2026-07-26T00:00:00Z")));
        }

        @Override
        public CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command) {
            return new CancelTurnOutcome.Rejected("CANCEL_RACE_LOST");
        }

        @Override
        public TurnAttemptLeasePort.HeartbeatOutcome heartbeat(FencedAttempt attempt) {
            return new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(1));
        }

        @Override
        public DeadlineCancelOutcome cancelAtDeadline(FencedAttempt attempt, org.zipp.ai.application.turn.AttemptDeadlineReason reason) {
            return new DeadlineCancelOutcome.TransientFailure("TEST_ONLY");
        }

        @Override
        public TurnAttemptTakeoverPort.TakeoverOutcome takeover(AuthenticatedActor actor, TurnKey key) {
            return new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
        }
    }
}
