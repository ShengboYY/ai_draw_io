package org.zipp.ai.application.turn.planning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Deterministic Direct/Composite planner over role-aware, owner-fenced Probe facts. */
public final class DirectCompositePlanner {

    public SourcePlanDecision plan(SourceProbeCommand command, SourceProbeOutcome outcome) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        if (!(command instanceof SourceProbeCommand.Direct)
                && !(command instanceof SourceProbeCommand.OptionalComposite)
                && !(command instanceof SourceProbeCommand.RequiredComposite)) {
            return new SourcePlanDecision.PlanningBlocked(
                    "DIRECT_OR_COMPOSITE_COMMAND_REQUIRED", false);
        }
        if (!command.binding().equals(outcome.binding())) {
            return blocked("CAPABILITY_SCOPE_MISMATCH");
        }
        if (outcome instanceof SourceProbeOutcome.Cancelled) {
            return blocked("CANCELLED");
        }
        if (outcome instanceof SourceProbeOutcome.Terminal terminal) {
            return blocked(terminal.reason());
        }
        // Direct is always required, so a globally unavailable Probe can never fallback.
        if (outcome instanceof SourceProbeOutcome.Unavailable unavailable) {
            return new SourcePlanDecision.PlanningBlocked(
                    "SOURCE_PROBE_" + unavailable.reason().name(), true);
        }

        SourceAvailability availability =
                ((SourceProbeOutcome.Available) outcome).availability();
        if (!factsMatchBinding(command.binding(), availability)) {
            return blocked("INVARIANT_BREACH");
        }
        if (command instanceof SourceProbeCommand.Direct) {
            return planDirect(command.binding(), availability);
        }
        if (!(availability instanceof SourceAvailability.Composite composite)) {
            return blocked("COMPOSITE_AVAILABILITY_REQUIRED");
        }
        return command instanceof SourceProbeCommand.OptionalComposite
                ? planOptionalComposite(command.binding(), composite)
                : planRequiredComposite(command.binding(), composite);
    }

    private SourcePlanDecision planDirect(
            SourceProbeBinding binding,
            SourceAvailability availability
    ) {
        if (!(availability instanceof SourceAvailability.SingleRole single)
                || single.role().role() != SourceRole.DIRECT) {
            return blocked("DIRECT_AVAILABILITY_REQUIRED");
        }
        DirectSelection selected = selectDirect(single.role());
        if (selected.decision() != null) {
            return selected.decision();
        }
        DirectSelector selector = new DirectSelector(selected.candidate());
        SourceAwareDrawPlan.Direct plan = new SourceAwareDrawPlan.Direct(selector);
        return new SourcePlanDecision.SourceReady(bound(
                binding, plan, new SourceExecutionEntry.Primary(),
                "DIRECT", selected.candidate().candidateRef()));
    }

    private SourcePlanDecision planOptionalComposite(
            SourceProbeBinding binding,
            SourceAvailability.Composite availability
    ) {
        DirectSelection selected = selectDirect(availability.direct());
        if (selected.decision() != null) {
            return selected.decision();
        }
        DirectSelector selector = new DirectSelector(selected.candidate());
        String branchId = digest(
                binding.lineage().value(),
                binding.declarationDigest(),
                selected.candidate().candidateRef(),
                "DIRECT_ONLY");
        ValidatedDirectOnlyFallback fallback =
                new ValidatedDirectOnlyFallback(selector, branchId);
        if (availability.retrieval() instanceof RoleAvailability.RetrievalAvailable retrieval) {
            SourceAwareDrawPlan.OptionalComposite plan =
                    new SourceAwareDrawPlan.OptionalComposite(
                            selector,
                            retrieval.candidates(),
                            DirectSourceReusePolicy.EXCLUDE_PRIMARY_FROM_RETRIEVAL,
                            fallback);
            return new SourcePlanDecision.SourceReady(bound(
                    binding, plan, new SourceExecutionEntry.Primary(),
                    "OPTIONAL_COMPOSITE", selected.candidate().candidateRef(),
                    String.join(",", refs(retrieval.candidates())), branchId));
        }

        RoleAvailability.Unavailable unavailable =
                (RoleAvailability.Unavailable) availability.retrieval();
        FallbackReason reason = optionalFallbackReason(unavailable.reason());
        if (reason == null) {
            return blocked("RETRIEVAL_" + unavailable.reason().name());
        }
        SourceAwareDrawPlan.OptionalComposite plan =
                new SourceAwareDrawPlan.OptionalComposite(
                        selector,
                        List.of(),
                        DirectSourceReusePolicy.EXCLUDE_PRIMARY_FROM_RETRIEVAL,
                        fallback);
        BoundSourcePlan bound = bound(
                binding,
                plan,
                new SourceExecutionEntry.SignedDirectOnly(branchId, reason),
                "OPTIONAL_COMPOSITE_DIRECT_ONLY",
                selected.candidate().candidateRef(),
                unavailable.reason().name(),
                branchId);
        return new SourcePlanDecision.DirectOnlyReady(bound);
    }

    private SourcePlanDecision planRequiredComposite(
            SourceProbeBinding binding,
            SourceAvailability.Composite availability
    ) {
        DirectSelection selected = selectDirect(availability.direct());
        if (selected.decision() != null) {
            return selected.decision();
        }
        if (!(availability.retrieval() instanceof RoleAvailability.RetrievalAvailable retrieval)) {
            RoleAvailability.Unavailable unavailable =
                    (RoleAvailability.Unavailable) availability.retrieval();
            return blocked("REQUIRED_RETRIEVAL_" + unavailable.reason().name());
        }
        SourceAwareDrawPlan.RequiredComposite plan =
                new SourceAwareDrawPlan.RequiredComposite(
                        new DirectSelector(selected.candidate()),
                        retrieval.candidates(),
                        DirectSourceReusePolicy.EXCLUDE_PRIMARY_FROM_RETRIEVAL);
        return new SourcePlanDecision.SourceReady(bound(
                binding, plan, new SourceExecutionEntry.Primary(),
                "REQUIRED_COMPOSITE", selected.candidate().candidateRef(),
                String.join(",", refs(retrieval.candidates()))));
    }

    private DirectSelection selectDirect(RoleAvailability availability) {
        if (availability instanceof RoleAvailability.Unavailable unavailable) {
            String code = unavailable.reason() == RoleUnavailability.NO_MATCH
                    ? "DIRECT_SOURCE_MISSING"
                    : "DIRECT_" + unavailable.reason().name();
            return new DirectSelection(null, blocked(code));
        }
        if (!(availability instanceof RoleAvailability.DirectAvailable direct)) {
            return new DirectSelection(null, blocked("DIRECT_AVAILABILITY_REQUIRED"));
        }
        if (direct.candidates().size() > 1) {
            return new DirectSelection(null, new SourcePlanDecision.NeedClarification(
                    "AMBIGUOUS_DIRECT_IMAGE",
                    direct.candidates().stream()
                            .map(DirectCandidateFact::clarificationRef)
                            .toList()));
        }
        return new DirectSelection(direct.candidates().get(0), null);
    }

    private boolean factsMatchBinding(
            SourceProbeBinding binding,
            SourceAvailability availability
    ) {
        if (availability instanceof SourceAvailability.SingleRole single) {
            return roleFactsMatch(binding, single.role());
        }
        SourceAvailability.Composite composite = (SourceAvailability.Composite) availability;
        return roleFactsMatch(binding, composite.direct())
                && roleFactsMatch(binding, composite.retrieval());
    }

    private boolean roleFactsMatch(SourceProbeBinding binding, RoleAvailability availability) {
        if (availability instanceof RoleAvailability.DirectAvailable direct) {
            return unique(direct.candidates().stream()
                    .map(DirectCandidateFact::candidateRef).toList())
                    && direct.candidates().stream()
                    .allMatch(candidate -> binding.equals(candidate.binding()));
        }
        if (availability instanceof RoleAvailability.RetrievalAvailable retrieval) {
            return unique(refs(retrieval.candidates()))
                    && retrieval.candidates().stream()
                    .allMatch(candidate -> binding.equals(candidate.binding()));
        }
        return true;
    }

    private boolean unique(List<String> values) {
        return values.stream().distinct().count() == values.size();
    }

    private List<String> refs(List<RetrievalCandidateFact> candidates) {
        return candidates.stream().map(RetrievalCandidateFact::candidateRef).toList();
    }

    private FallbackReason optionalFallbackReason(RoleUnavailability reason) {
        return switch (reason) {
            case NO_MATCH -> FallbackReason.SOURCE_NO_MATCH;
            case PROCESSING, DEPENDENCY_UNAVAILABLE -> FallbackReason.SOURCE_UNAVAILABLE;
            case AUTHORIZATION_VIOLATION, REQUIRED_CONFLICT -> null;
        };
    }

    private BoundSourcePlan bound(
            SourceProbeBinding binding,
            SourceAwareDrawPlan plan,
            SourceExecutionEntry entry,
            String... graph
    ) {
        String fingerprint = digest(
                binding.lineage().value(),
                binding.declarationDigest(),
                String.join("\u001f", graph));
        return new BoundSourcePlan(
                plan,
                new SourcePlanIdentity(binding.lineage(), fingerprint),
                entry);
    }

    private SourcePlanDecision.PlanningBlocked blocked(String code) {
        return new SourcePlanDecision.PlanningBlocked(code, false);
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
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record DirectSelection(
            DirectCandidateFact candidate,
            SourcePlanDecision decision
    ) {
    }
}
