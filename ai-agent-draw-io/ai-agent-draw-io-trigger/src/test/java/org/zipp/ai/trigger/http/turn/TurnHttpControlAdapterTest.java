package org.zipp.ai.trigger.http.turn;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.AttemptDeadlineReason;
import org.zipp.ai.application.turn.AuthenticatedActor;
import org.zipp.ai.application.turn.CancelTurnCommand;
import org.zipp.ai.application.turn.CancelTurnOutcome;
import org.zipp.ai.application.turn.ConversationCatalogPort;
import org.zipp.ai.application.turn.ConversationRef;
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

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TurnHttpControlAdapterTest {

    @Test
    void resolvesCanonicalReferenceBeforeQueryingStatus() {
        RecordingControl control = new RecordingControl();
        TurnHttpControlAdapter adapter = new TurnHttpControlAdapter(
                new FakeConversationCatalog(),
                new org.zipp.ai.application.turn.ConversationReferenceResolver(),
                control);

        TurnStatusQueryOutcome outcome = adapter.status(
                new AuthenticatedActor("owner-1", "cohort-1"),
                new TurnHttpControlRequest("turn-1", "conversation:conversation-1", "diagram-1"));

        assertInstanceOf(TurnStatusQueryOutcome.Available.class, outcome);
        assertEquals(new TurnKey("owner-1", "conversation-1", "turn-1"), control.status.key());
    }

    @Test
    void legacyAliasUsesTheSameCanonicalKeyForCancellation() {
        RecordingControl control = new RecordingControl();
        TurnHttpControlAdapter adapter = new TurnHttpControlAdapter(
                new FakeConversationCatalog(),
                new org.zipp.ai.application.turn.ConversationReferenceResolver(),
                control);

        CancelTurnOutcome outcome = adapter.cancel(
                new AuthenticatedActor("owner-1", "cohort-1"),
                new TurnHttpCancelRequest(
                        new TurnHttpControlRequest("turn-1", "legacy:session-1", "diagram-1"),
                        "user_requested"));

        assertInstanceOf(CancelTurnOutcome.Rejected.class, outcome);
        assertEquals(new TurnKey("owner-1", "conversation-1", "turn-1"), control.cancel.key());
        assertEquals("user_requested", control.cancel.reason());
    }

    private static final class RecordingControl implements TurnControlFacade {
        private TurnStatusQuery status;
        private CancelTurnCommand cancel;

        @Override
        public TurnStatusQueryOutcome status(AuthenticatedActor actor, TurnStatusQuery query) {
            status = query;
            return new TurnStatusQueryOutcome.Available(new TurnStatusView(
                    query.key(), TurnStatus.RUNNING, "attempt-1", 1, null, null,
                    Instant.parse("2026-07-26T00:00:00Z")));
        }

        @Override
        public CancelTurnOutcome cancel(AuthenticatedActor actor, CancelTurnCommand command) {
            cancel = command;
            return new CancelTurnOutcome.Rejected("TEST_ONLY");
        }

        @Override
        public TurnAttemptLeasePort.HeartbeatOutcome heartbeat(FencedAttempt attempt) {
            return new TurnAttemptLeasePort.LeaseTransientFailure(Duration.ofSeconds(1));
        }

        @Override
        public DeadlineCancelOutcome cancelAtDeadline(FencedAttempt attempt, AttemptDeadlineReason reason) {
            return new DeadlineCancelOutcome.TransientFailure("TEST_ONLY");
        }

        @Override
        public TurnAttemptTakeoverPort.TakeoverOutcome takeover(AuthenticatedActor actor, TurnKey key) {
            return new TurnAttemptTakeoverPort.Rejected("TEST_ONLY");
        }
    }

    private static final class FakeConversationCatalog implements ConversationCatalogPort {
        @Override
        public ConversationRef findOrCreateDefault(AuthenticatedActor actor, String diagramId) {
            return conversation(actor);
        }

        @Override
        public ConversationRef requireActiveBinding(
                AuthenticatedActor actor, String conversationId, String diagramId
        ) {
            return conversation(actor);
        }

        @Override
        public ConversationRef resolveLegacyAlias(
                AuthenticatedActor actor, String legacySessionId, String diagramId
        ) {
            return conversation(actor);
        }

        private ConversationRef conversation(AuthenticatedActor actor) {
            return new ConversationRef("conversation-1", actor.ownerKey(), "diagram-1", ConversationStatus.ACTIVE);
        }
    }
}
