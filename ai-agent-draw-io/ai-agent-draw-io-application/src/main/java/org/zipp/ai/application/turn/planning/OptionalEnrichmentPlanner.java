package org.zipp.ai.application.turn.planning;

import java.util.Objects;

/** Applies requiredness after Probe without allowing Probe adapters to choose fallback policy. */
public final class OptionalEnrichmentPlanner {

    public SourcePlanDecision plan(SourceProbeCommand command, SourceProbeOutcome outcome) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        if (!command.binding().equals(outcome.binding())) {
            return new SourcePlanDecision.PlanningBlocked("CAPABILITY_SCOPE_MISMATCH", false);
        }
        if (outcome instanceof SourceProbeOutcome.Cancelled) {
            return new SourcePlanDecision.PlanningBlocked("CANCELLED", false);
        }
        if (outcome instanceof SourceProbeOutcome.Terminal terminal) {
            return new SourcePlanDecision.PlanningBlocked(terminal.reason(), false);
        }
        if (outcome instanceof SourceProbeOutcome.Unavailable unavailable) {
            if (command instanceof SourceProbeCommand.OptionalDiscovery optional) {
                return new SourcePlanDecision.ProbeFallbackReady(
                        optional.validatedProbeFallback(),
                        fallbackReason(unavailable.reason()),
                        command.binding().lineage());
            }
            return new SourcePlanDecision.PlanningBlocked(
                    "REQUIRED_SOURCE_" + unavailable.reason().name(), true);
        }

        SourceProbeOutcome.Available available = (SourceProbeOutcome.Available) outcome;
        java.util.List<String> candidateRefs = retrievalCandidates(
                command.binding(), available.availability());
        if (candidateRefs == null) {
            return new SourcePlanDecision.PlanningBlocked("INVARIANT_BREACH", false);
        }
        if (command instanceof SourceProbeCommand.OptionalDiscovery optional) {
            return new SourcePlanDecision.OptionalRetrievalReady(
                    new OptionalRetrievalDrawPlan(
                            candidateRefs,
                            optional.validatedProbeFallback(),
                            command.binding().lineage()));
        }
        return new SourcePlanDecision.RequiredSourceReady(
                candidateRefs, command.binding().lineage());
    }

    private FallbackReason fallbackReason(SourceProbeOutcome.Unavailability reason) {
        return switch (reason) {
            case NO_MATCH -> FallbackReason.SOURCE_NO_MATCH;
            case TIMEOUT, DEPENDENCY_UNAVAILABLE -> FallbackReason.SOURCE_UNAVAILABLE;
        };
    }

    private java.util.List<String> retrievalCandidates(
            SourceProbeBinding binding,
            SourceAvailability availability
    ) {
        if (!(availability instanceof SourceAvailability.SingleRole single)
                || !(single.role() instanceof RoleAvailability.RetrievalAvailable retrieval)
                || retrieval.candidates().stream()
                .anyMatch(candidate -> !binding.equals(candidate.binding()))) {
            return null;
        }
        return retrieval.candidates().stream()
                .map(RetrievalCandidateFact::candidateRef)
                .toList();
    }
}
