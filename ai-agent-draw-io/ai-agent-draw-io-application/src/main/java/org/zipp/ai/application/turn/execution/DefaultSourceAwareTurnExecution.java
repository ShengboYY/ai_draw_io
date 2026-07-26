package org.zipp.ai.application.turn.execution;

import org.zipp.ai.application.turn.DirectTurnHandler;
import org.zipp.ai.application.turn.EvidenceAnswerTurnHandler;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.GroundedTurnHandler;
import org.zipp.ai.application.turn.SourceAwarePreparedExecution;
import org.zipp.ai.application.turn.SourceAwarePreparationPort;
import org.zipp.ai.application.turn.SourceExecutionBindingOutcome;
import org.zipp.ai.application.turn.SourceExecutionBindingPort;
import org.zipp.ai.application.turn.TurnFailureCode;
import org.zipp.ai.application.turn.TurnStatusRef;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.planning.DirectCompositePlanner;
import org.zipp.ai.application.turn.planning.OptionalEnrichmentPlanner;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.SourcePlanDecision;
import org.zipp.ai.application.turn.planning.SourceProbeCommand;
import org.zipp.ai.application.turn.planning.SourceProbeContext;
import org.zipp.ai.application.turn.planning.SourceProbeOutcome;
import org.zipp.ai.application.turn.planning.SourceProbePort;
import org.zipp.ai.application.turn.planning.TurnRouteDecision;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;

/**
 * Coordinates Probe, policy planning, capability preparation, and the typed source handler. A
 * missing production capability is returned as a durable rejection by the outer V2 executor.
 */
public final class DefaultSourceAwareTurnExecution implements SourceAwareTurnExecution {

    private final SourceProbePort probe;
    private final DirectCompositePlanner directPlanner;
    private final OptionalEnrichmentPlanner enrichmentPlanner;
    private final SourceAwarePreparationPort preparation;
    private final SourceExecutionBindingPort sourceBinding;
    private final Optional<DirectTurnHandler> direct;
    private final Optional<GroundedTurnHandler> grounded;
    private final Optional<EvidenceAnswerTurnHandler> evidenceAnswer;

    public DefaultSourceAwareTurnExecution(
            SourceProbePort probe,
            DirectCompositePlanner directPlanner,
            OptionalEnrichmentPlanner enrichmentPlanner,
            SourceAwarePreparationPort preparation,
            SourceExecutionBindingPort sourceBinding,
            Optional<DirectTurnHandler> direct,
            Optional<GroundedTurnHandler> grounded,
            Optional<EvidenceAnswerTurnHandler> evidenceAnswer
    ) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.directPlanner = Objects.requireNonNull(directPlanner, "directPlanner");
        this.enrichmentPlanner = Objects.requireNonNull(enrichmentPlanner, "enrichmentPlanner");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.sourceBinding = Objects.requireNonNull(sourceBinding, "sourceBinding");
        this.direct = Objects.requireNonNull(direct, "direct");
        this.grounded = Objects.requireNonNull(grounded, "grounded");
        this.evidenceAnswer = Objects.requireNonNull(evidenceAnswer, "evidenceAnswer");
    }

    @Override
    public TurnV2ExecutionOutcome execute(
            FencedAttempt attempt,
            UserTurnCommand command,
            TurnV2PreHandlerOutcome.Ready prepared,
            TurnEventSink events
    ) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(events, "events");
        if (!(prepared.decision() instanceof TurnRouteDecision.SourcePlanning sourcePlanning)) {
            return new TurnV2ExecutionOutcome.NotDispatched(
                    prepared.decision(), "SOURCE_AWARE_ROUTE_REQUIRED");
        }

        PrePlanOutcome.SourcePlanningRequired required = sourcePlanning.value();
        SourceProbeCommand probeCommand;
        SourceAwarePreparedExecution sourceExecution;
        try {
            probeCommand = SourceProbeCommand.from(required);
            SourceProbeOutcome probeOutcome = probe.probe(probeCommand, new SourceProbeContext(
                    "USER", attempt.key().ownerKey(), prepared.context().request().diagramId(),
                    required.turn().canonicalConversationId(), required.turn().turnId()));
            SourcePlanDecision plan = plan(probeCommand, probeOutcome);
            if (!(plan instanceof SourcePlanDecision.SourceReady sourceReady)
                    && !(plan instanceof SourcePlanDecision.DirectOnlyReady directOnlyReady)
                    && !(plan instanceof SourcePlanDecision.RequiredSourceReady requiredReady)) {
                return rejected(prepared.decision(), rejectionCode(plan));
            }
            SourceAwarePreparationPort.Outcome preparedSource = preparation.prepare(
                    new SourceAwarePreparationPort.Request(
                            attempt, prepared.context(), prepared.readSet(), probeCommand, plan));
            if (!(preparedSource instanceof SourceAwarePreparationPort.Outcome.Ready ready)) {
                return rejected(prepared.decision(),
                        ((SourceAwarePreparationPort.Outcome.Rejected) preparedSource).code());
            }
            SourceExecutionBindingOutcome pinned = sourceBinding.pin(
                    attempt, binding(ready.execution()));
            if (pinned instanceof SourceExecutionBindingOutcome.FenceLost lost) {
                return new TurnV2ExecutionOutcome.PreparationBlocked(
                        new TurnV2PreHandlerOutcome.FenceLost(
                                new TurnStatusRef(lost.status().key())));
            }
            if (pinned instanceof SourceExecutionBindingOutcome.TerminalUnavailable unavailable) {
                return new TurnV2ExecutionOutcome.PreparationBlocked(
                        new TurnV2PreHandlerOutcome.Unavailable(
                                new TurnStatusRef(unavailable.status().key()),
                                TurnFailureCode.TERMINAL_UNAVAILABLE,
                                Duration.ZERO));
            }
            if (pinned instanceof SourceExecutionBindingOutcome.Rejected rejection) {
                return rejected(prepared.decision(), rejection.code());
            }
            sourceExecution = ready.execution();
        } catch (RuntimeException failure) {
            // Source probing/preparation must not turn a required source request into a plain draw.
            return rejected(prepared.decision(), "SOURCE_AWARE_PREPARATION_FAILED");
        }
        // Handler/model failures remain execution failures and are handled by the outer attempt
        // supervisor; only capability gaps above become a durable route rejection.
        return dispatch(attempt, prepared, sourceExecution, events);
    }

    private SourcePlanDecision plan(SourceProbeCommand command, SourceProbeOutcome outcome) {
        return command instanceof SourceProbeCommand.Direct
                || command instanceof SourceProbeCommand.OptionalComposite
                || command instanceof SourceProbeCommand.RequiredComposite
                ? directPlanner.plan(command, outcome)
                : enrichmentPlanner.plan(command, outcome);
    }

    private TurnV2ExecutionOutcome dispatch(
            FencedAttempt attempt,
            TurnV2PreHandlerOutcome.Ready prepared,
            SourceAwarePreparedExecution execution,
            TurnEventSink events
    ) {
        if (execution instanceof SourceAwarePreparedExecution.Direct value) {
            if (direct.isEmpty()) {
                return rejected(prepared.decision(), "DIRECT_HANDLER_NOT_AVAILABLE");
            }
            return committed(direct.get().execute(
                    attempt, prepared.context(), prepared.readSet(), value.plan(),
                    value.sourceBinding(), value.artifactLeaseRef(), value.sourceIdentityRef(),
                    value.origin(), events));
        }
        if (execution instanceof SourceAwarePreparedExecution.Grounded value) {
            if (grounded.isEmpty()) {
                return rejected(prepared.decision(), "GROUNDED_HANDLER_NOT_AVAILABLE");
            }
            return committed(grounded.get().execute(
                    attempt, prepared.context(), prepared.readSet(), value.plan(),
                    value.sourceBinding(), value.preparedEvidenceRef(), value.citations(),
                    value.directProvenance(), events));
        }
        SourceAwarePreparedExecution.EvidenceAnswer value =
                (SourceAwarePreparedExecution.EvidenceAnswer) execution;
        if (evidenceAnswer.isEmpty()) {
            return rejected(prepared.decision(), "EVIDENCE_ANSWER_HANDLER_NOT_AVAILABLE");
        }
        return committed(evidenceAnswer.get().execute(
                attempt, prepared.context(), prepared.readSet(), value.sourceBinding(),
                value.preparedEvidenceRef(), value.citations(), events));
    }

    private TurnV2ExecutionOutcome committed(org.zipp.ai.application.turn.FencedCommitOutcome outcome) {
        return new TurnV2ExecutionOutcome.Committed(outcome);
    }

    private org.zipp.ai.application.turn.SourceCommitBinding binding(
            SourceAwarePreparedExecution execution) {
        if (execution instanceof SourceAwarePreparedExecution.Direct direct) {
            return direct.sourceBinding();
        }
        if (execution instanceof SourceAwarePreparedExecution.Grounded grounded) {
            return grounded.sourceBinding();
        }
        return ((SourceAwarePreparedExecution.EvidenceAnswer) execution).sourceBinding();
    }

    private TurnV2ExecutionOutcome rejected(
            TurnRouteDecision decision,
            String code
    ) {
        return new TurnV2ExecutionOutcome.NotDispatched(decision, code);
    }

    private String rejectionCode(SourcePlanDecision decision) {
        if (decision instanceof SourcePlanDecision.PlanningBlocked blocked) {
            return blocked.reason();
        }
        if (decision instanceof SourcePlanDecision.NeedClarification clarification) {
            return clarification.code();
        }
        if (decision instanceof SourcePlanDecision.ProbeFallbackReady) {
            return "SOURCE_AWARE_FALLBACK_NOT_EXECUTABLE";
        }
        if (decision instanceof SourcePlanDecision.OptionalRetrievalReady) {
            return "OPTIONAL_RETRIEVAL_HANDLER_NOT_AVAILABLE";
        }
        return "SOURCE_AWARE_PLAN_NOT_EXECUTABLE";
    }
}
