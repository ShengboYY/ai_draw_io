package org.zipp.ai.application.turn.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        RoleAvailability.RetrievalAvailable retrieval =
                (RoleAvailability.RetrievalAvailable) ((SourceAvailability.SingleRole)
                        available.availability()).role();
        SourceAwareDrawPlan.Retrieval drawPlan =
                new SourceAwareDrawPlan.Retrieval(retrieval.candidates());
        String fingerprint = digest(command.binding().lineage().value(),
                command.binding().declarationDigest(), String.join("\u001f", candidateRefs),
                "RETRIEVAL");
        BoundSourcePlan bound = new BoundSourcePlan(
                drawPlan,
                new SourcePlanIdentity(command.binding().lineage(), fingerprint),
                new SourceExecutionEntry.Primary());
        return new SourcePlanDecision.RequiredSourceReady(
                candidateRefs, command.binding().lineage(), bound);
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

    private String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
