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
    private final TurnLifecycleTracePort trace;

    public DefaultDiagramTurnFacade(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnAdmissionProfilePort profile,
            TurnEngineAdmissionService admission,
            TurnStartCommitPort turnStart,
            AdmissionBarrier admissionGate
    ) {
        this(conversations, conversationResolver, profile, admission, turnStart, admissionGate,
                NoopTurnLifecycleTracePort.INSTANCE);
    }

    public DefaultDiagramTurnFacade(
            ConversationCatalogPort conversations,
            ConversationReferenceResolver conversationResolver,
            TurnAdmissionProfilePort profile,
            TurnEngineAdmissionService admission,
            TurnStartCommitPort turnStart,
            AdmissionBarrier admissionGate,
            TurnLifecycleTracePort trace
    ) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.conversationResolver = Objects.requireNonNull(conversationResolver, "conversationResolver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.turnStart = Objects.requireNonNull(turnStart, "turnStart");
        this.admissionGate = Objects.requireNonNull(admissionGate, "admissionGate");
        this.trace = Objects.requireNonNull(trace, "trace");
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
        if (!admissionGate.tryEnter()) {
            return new TurnSubmission.NotReady("TURN_INSTANCE_NOT_READY");
        }

        try {
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
            AdmissionWriteOutcome admissionOutcome = admission.admitAfterEntry(
                    actor, conversation, command, fingerprints, policy);
            return submitClaim(key, command, admissionOutcome);
        } finally {
            admissionGate.leave();
        }
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
        if (assignment.selectedEngine() == SelectedTurnEngine.LEGACY) {
            // The bridge has crossed the only assignment boundary. Do not create a V2 attempt
            // for a key whose durable assignment requires V1 handling.
            return new TurnSubmission.LegacyHandoff(key);
        }
        String inputBindingDigest = TurnInputBindingDigestCalculator.current(command);
        trace.recordSafely(TurnLifecycleTraceEvent.of(
                TurnLifecycleTraceType.ASSIGNMENT,
                key,
                null,
                0,
                assignment.policy().policyHash(),
                inputBindingDigest,
                admissionOutcome instanceof AdmissionWriteOutcome.Assigned ? "ASSIGNED" : "REUSED",
                null));
        long callStartedNanos = System.nanoTime();
        TurnStartOutcome startOutcome = turnStart.start(new TurnStartCommand(
                key,
                command.diagramId(),
                assignment,
                command.content(),
                command.clientMessageId(),
                command.declarations().currentTurnAttachments(),
                command.declarations(),
                inputBindingDigest));
        if (startOutcome instanceof TurnStartOutcome.Claimed claimed) {
            trace.recordSafely(TurnLifecycleTraceEvent.fromAttempt(
                    TurnLifecycleTraceType.CLAIM, claimed.attempt(), "CLAIMED", TurnStatus.RUNNING));
            return new TurnSubmission.ExecutionAccepted(
                    key,
                    claimed.attempt(),
                    new LeaseTimingAnchor(callStartedNanos, claimed.attempt().lease()));
        }
        if (startOutcome instanceof TurnStartOutcome.AlreadyRunning running) {
            trace.recordSafely(statusTrace(
                    key, assignment, inputBindingDigest, running.status(), "ALREADY_RUNNING"));
            return new TurnSubmission.AlreadyRunning(key, running.status());
        }
        if (startOutcome instanceof TurnStartOutcome.TerminalReplay replay) {
            trace.recordSafely(TurnLifecycleTraceEvent.of(
                    TurnLifecycleTraceType.CLAIM, key, null, 0,
                    assignment.policy().policyHash(), inputBindingDigest, "TERMINAL_REPLAY",
                    replay.outcome().status()));
            return new TurnSubmission.TerminalReplay(key, replay.outcome());
        }
        if (startOutcome instanceof TurnStartOutcome.TerminalUnavailable unavailable) {
            trace.recordSafely(statusTrace(
                    key, assignment, inputBindingDigest, unavailable.status(), unavailable.code()));
            return new TurnSubmission.TerminalUnavailable(key, unavailable.status(), unavailable.code());
        }
        String code = ((TurnStartOutcome.Rejected) startOutcome).code();
        trace.recordSafely(TurnLifecycleTraceEvent.of(
                TurnLifecycleTraceType.CLAIM, key, null, 0,
                assignment.policy().policyHash(), inputBindingDigest, code, null));
        return admissionRejection(key, code);
    }

    private TurnLifecycleTraceEvent statusTrace(
            TurnKey key,
            TurnEngineAssignment assignment,
            String inputBindingDigest,
            TurnStatusView status,
            String outcomeCode
    ) {
        return TurnLifecycleTraceEvent.of(
                TurnLifecycleTraceType.CLAIM,
                key,
                status.attemptId(),
                status.attemptEpoch(),
                assignment.policy().policyHash(),
                inputBindingDigest,
                outcomeCode,
                status.status());
    }

    private TurnSubmission admissionRejection(TurnKey key, String code) {
        if ("REQUEST_FINGERPRINT_CONFLICT".equals(code)) {
            return new TurnSubmission.IdempotencyConflict(key, code);
        }
        return new TurnSubmission.AdmissionRejected(key, code);
    }
}
