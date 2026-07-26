package org.zipp.ai.application.turn.planning;

import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Probe input built only from the complete pre-plan result. Required commands deliberately expose
 * no fallback accessor.
 */
public sealed interface SourceProbeCommand
        permits SourceProbeCommand.Direct,
        SourceProbeCommand.Required,
        SourceProbeCommand.OptionalDiscovery,
        SourceProbeCommand.OptionalComposite,
        SourceProbeCommand.RequiredComposite {

    SourceProbeBinding binding();

    AcceptedSourceDemand demand();

    static SourceProbeCommand from(PrePlanOutcome.SourcePlanningRequired required) {
        if (required == null) {
            throw new IllegalArgumentException("source planning requirement must not be null");
        }
        SourceProbeBinding binding = new SourceProbeBinding(
                required.turn(),
                required.lineage(),
                declarationDigest(required.accepted()),
                required.contextReadSetDigest(),
                required.inputBindingDigest());
        return switch (required.accepted().kind()) {
            case CURRENT_MESSAGE_ATTACHMENTS_REQUIRED, CURRENT_MESSAGE_DIRECT_REQUIRED ->
                    new Direct(binding, required.accepted());
            case CURRENT_MESSAGE_RETRIEVAL_REQUIRED ->
                    new Required(binding, required.accepted());
            case OPTIONAL_DISCOVERY -> optionalDiscovery(required, binding);
            case CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL ->
                    new OptionalComposite(binding, required.accepted());
            case CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED ->
                    new RequiredComposite(binding, required.accepted());
        };
    }

    private static OptionalDiscovery optionalDiscovery(
            PrePlanOutcome.SourcePlanningRequired required,
            SourceProbeBinding binding
    ) {
        PlainDrawAction action = switch (required.intent().action()) {
            case CREATE -> PlainDrawAction.CREATE;
            case EDIT -> PlainDrawAction.EDIT;
            case LAYOUT -> PlainDrawAction.LAYOUT;
            default -> throw new IllegalArgumentException("OPTIONAL_DISCOVERY_REQUIRES_PLAIN_DRAW");
        };
        PlainDrawPlan plan = new PlainDrawPlan(action, required.instruction().value());
        String branchId = digest(
                binding.lineage().value(), binding.declarationDigest(),
                binding.contextReadSetDigest(), binding.inputBindingDigest(),
                action.name(), plan.instruction());
        return new OptionalDiscovery(
                binding, required.accepted(), new ValidatedPlainFallback(branchId, plan));
    }

    record Direct(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand
    ) implements SourceProbeCommand {
        public Direct {
            if (binding == null || demand == null || !isDirectOnly(demand.kind())
                    || !validRefs(demand.attachmentRefs())
                    || demand.relevanceQuery() != null) {
                throw new IllegalArgumentException("invalid Direct Probe command");
            }
        }
    }

    record Required(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand
    ) implements SourceProbeCommand {
        public Required {
            if (binding == null || demand == null
                    || demand.kind() != SourceDemandKind.CURRENT_MESSAGE_RETRIEVAL_REQUIRED
                    || !validRefs(demand.attachmentRefs())
                    || demand.relevanceQuery() != null) {
                throw new IllegalArgumentException("invalid required Probe command");
            }
        }
    }

    record OptionalComposite(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand
    ) implements SourceProbeCommand {
        public OptionalComposite {
            if (binding == null || demand == null
                    || demand.kind()
                    != SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_OPTIONAL
                    || !validRefs(demand.attachmentRefs())
                    || demand.relevanceQuery() == null
                    || demand.relevanceQuery().isBlank()) {
                throw new IllegalArgumentException("invalid Optional Composite Probe command");
            }
        }
    }

    record RequiredComposite(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand
    ) implements SourceProbeCommand {
        public RequiredComposite {
            if (binding == null || demand == null
                    || demand.kind()
                    != SourceDemandKind.CURRENT_MESSAGE_DIRECT_RETRIEVAL_REQUIRED
                    || !validRefs(demand.attachmentRefs())
                    || demand.relevanceQuery() == null
                    || demand.relevanceQuery().isBlank()) {
                throw new IllegalArgumentException("invalid Required Composite Probe command");
            }
        }
    }

    private static boolean isDirectOnly(SourceDemandKind kind) {
        return kind == SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED
                || kind == SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED;
    }

    private static boolean validRefs(List<String> refs) {
        return refs != null && !refs.isEmpty()
                && refs.stream().noneMatch(value -> value == null || value.isBlank())
                && refs.stream().distinct().count() == refs.size();
    }

    private static String declarationDigest(AcceptedSourceDemand demand) {
        return digest(
                demand.kind().name(),
                String.join("\u001f", demand.attachmentRefs()),
                demand.relevanceQuery() == null ? "" : demand.relevanceQuery());
    }

    record OptionalDiscovery(
            SourceProbeBinding binding,
            AcceptedSourceDemand demand,
            ValidatedPlainFallback validatedProbeFallback
    ) implements SourceProbeCommand {
        public OptionalDiscovery {
            if (binding == null || demand == null || validatedProbeFallback == null
                    || demand.kind() != SourceDemandKind.OPTIONAL_DISCOVERY
                    || demand.relevanceQuery() == null || demand.relevanceQuery().isBlank()) {
                throw new IllegalArgumentException("invalid Optional Discovery Probe command");
            }
        }
    }

    private static String digest(String... values) {
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
}
