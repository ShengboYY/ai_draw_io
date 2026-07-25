package org.zipp.ai.application.turn;

import java.util.Objects;

/**
 * Application-owned M1 submission boundary. It claims durable work but does not execute a
 * model, resolve sources, or choose behavior based on the sync/stream transport.
 */
public final class DefaultDiagramTurnFacade implements DiagramTurnFacade {

    private final ConversationCatalogPort conversations;
    private final ConversationReferenceResolver conversationResolver;
    private final TurnAdmissionProfilePort profile;
    private final TurnEngineAdmissionService admission;
    private final TurnStartCommitPort turnStart;
    private final AdmissionBarrier admissionGate;

    public DefaultDiagramTurnFacade(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnAdmissionProfilePort profile,
            TurnEngineAdmissionService admission,
            TurnStartCommitPort turnStart,
            AdmissionBarrier admissionGate
    ) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.conversationResolver = Objects.requireNonNull(conversationResolver, "conversationResolver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.turnStart = Objects.requireNonNull(turnStart, "turnStart");
        this.admissionGate = Objects.requireNonNull(admissionGate, "admissionGate");
    }

    @Override
    public TurnSubmission execute(
            AuthenticatedActor actor,
            UserTurnCommand command,
            TurnEventSink events
    ) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(events, "events");
        if (!admissionGate.isOpen()) {
            return new TurnSubmission.NotReady("TURN_INSTANCE_NOT_READY");
        }

        ConversationRef conversation = conversationResolver.resolve(conversations, actor, command);
        TurnKey key = TurnKey.of(actor, conversation, command.turnId());
        if (!conversation.diagramId().equals(command.diagramId())) {
            return new TurnSubmission.AdmissionRejected(key, "DIAGRAM_BINDING_MISMATCH");
        }
        if (!conversation.isActive()) {
            return new TurnSubmission.AdmissionRejected(key, "CONVERSATION_NOT_ACTIVE");
        }

        VersionedRequestFingerprintSet fingerprints = Objects.requireNonNull(
                profile.fingerprints(command), "profile fingerprints");
        ExecutionPolicySnapshot policy = Objects.requireNonNull(
                profile.policy(actor, conversation, command), "profile policy");
        AdmissionWriteOutcome admissionOutcome = admission.admit(
                actor, conversation, command, fingerprints, policy);
        return submitClaim(key, command, admissionOutcome);
    }

    private TurnSubmission submitClaim(
            TurnKey key,
            UserTurnCommand command,
            AdmissionWriteOutcome admissionOutcome
    ) {
        if (admissionOutcome instanceof AdmissionWriteOutcome.LegacyRetryGone gone) {
            return new TurnSubmission.LegacyRetryExpired(gone.key(), gone.reason());
        }
        if (admissionOutcome instanceof AdmissionWriteOutcome.Rejected rejected) {
            return admissionRejection(rejected.key(), rejected.code());
        }

        TurnEngineAssignment assignment = admissionOutcome instanceof AdmissionWriteOutcome.Assigned assigned
                ? assigned.assignment()
                : ((AdmissionWriteOutcome.Reused) admissionOutcome).assignment();
        long callStartedNanos = System.nanoTime();
        TurnStartOutcome startOutcome = turnStart.start(new TurnStartCommand(
                key,
                command.diagramId(),
                assignment,
                command.content(),
                command.clientMessageId(),
                command.declarations().currentTurnAttachments(),
                TurnInputBindingDigestCalculator.current(command)));
        if (startOutcome instanceof TurnStartOutcome.Claimed claimed) {
            return new TurnSubmission.ExecutionAccepted(
                    key,
                    claimed.attempt(),
                    new LeaseTimingAnchor(callStartedNanos, claimed.attempt().lease()));
        }
        if (startOutcome instanceof TurnStartOutcome.AlreadyRunning running) {
            return new TurnSubmission.AlreadyRunning(key, running.status());
        }
        if (startOutcome instanceof TurnStartOutcome.TerminalReplay replay) {
            return new TurnSubmission.TerminalReplay(key, replay.outcome());
        }
        return admissionRejection(key, ((TurnStartOutcome.Rejected) startOutcome).code());
    }

    private TurnSubmission admissionRejection(TurnKey key, String code) {
        if ("REQUEST_FINGERPRINT_CONFLICT".equals(code)) {
            return new TurnSubmission.IdempotencyConflict(key, code);
        }
        return new TurnSubmission.AdmissionRejected(key, code);
    }
}
